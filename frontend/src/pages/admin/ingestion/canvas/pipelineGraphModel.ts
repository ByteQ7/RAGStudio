import { MarkerType, type Connection, type Edge, type Node, type XYPosition } from "@xyflow/react";

import type { IngestionGraph, IngestionNodeKind } from "@/services/ingestionService";

import {
  buildGraphNodeData,
  buildNodeForm,
  createNodeForm,
  getNodeTypeShortLabel,
  type PipelineNodeForm,
  type PipelineNodeType
} from "../pipeline/pipelineFormModel";

/** 画布节点数据：processor 持有完整表单模型，condition 持有备注 */
export interface IngestionFlowNodeData extends Record<string, unknown> {
  /** processor 节点的表单模型（与编辑弹窗同构） */
  form?: PipelineNodeForm;
  /** condition 节点的展示备注 */
  note?: string;
}

export type IngestionFlowNode = Node<IngestionFlowNodeData, IngestionNodeKind>;
export type IngestionFlowEdge = Edge;

export const NODE_SIZE: Record<IngestionNodeKind, { width: number; height: number }> = {
  start: { width: 120, height: 44 },
  processor: { width: 240, height: 92 },
  condition: { width: 200, height: 76 },
  end: { width: 120, height: 44 }
};

let idSeq = 0;

export function newId(prefix: string): string {
  idSeq += 1;
  return `${prefix}-${Date.now().toString(36)}${idSeq.toString(36)}`;
}

/** 新建处理器节点 */
export function createProcessorNode(nodeType: PipelineNodeForm["nodeType"], position: XYPosition): IngestionFlowNode {
  const form = createNodeForm(nodeType);
  form.nodeId = `step-${newId("id").slice(-6)}`;
  return {
    id: form.nodeId,
    type: "processor",
    position,
    data: { form }
  };
}

/** 新建条件网关节点 */
export function createConditionNode(position: XYPosition): IngestionFlowNode {
  const id = newId("cond");
  return {
    id,
    type: "condition",
    position,
    data: { note: "分支" }
  };
}

/** 后端图 → React Flow 节点 */
export function toFlowNodes(graph: IngestionGraph | null | undefined): IngestionFlowNode[] {
  if (!graph?.nodes?.length) {
    return [];
  }
  return graph.nodes.map((node) => {
    if (node.kind === "processor") {
      const form = buildNodeForm({
        nodeId: node.id,
        nodeType: node.data?.nodeType ?? "fetcher",
        nextNodeId: null,
        condition: node.data?.condition ?? null,
        settings: node.data?.settings ?? null
      });
      return {
        id: node.id,
        type: "processor" as const,
        position: { x: node.x ?? 0, y: node.y ?? 0 },
        data: { form }
      };
    }
    return {
      id: node.id,
      type: node.kind,
      position: { x: node.x ?? 0, y: node.y ?? 0 },
      data: { note: node.data?.note ?? undefined }
    };
  });
}

/** 后端图 → React Flow 连线 */
export function toFlowEdges(graph: IngestionGraph | null | undefined): IngestionFlowEdge[] {
  if (!graph?.edges?.length) {
    return [];
  }
  return graph.edges.map((edge) =>
    makeEdge(edge.id, edge.source, edge.target, edge.label ?? undefined, edge.condition ?? undefined)
  );
}

export function makeEdge(
  id: string,
  source: string,
  target: string,
  label?: string,
  condition?: Record<string, unknown> | null
): IngestionFlowEdge {
  return {
    id,
    source,
    target,
    label: label || undefined,
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
    data: { condition: condition ?? null }
  };
}

/** React Flow 节点/连线 → 后端图 */
export function toApiGraph(nodes: IngestionFlowNode[], edges: IngestionFlowEdge[]): IngestionGraph {
  return {
    version: 1,
    nodes: nodes.map((node) => {
      if (node.type === "processor") {
        const form = node.data.form as PipelineNodeForm;
        const { settings, condition } = buildGraphNodeData(form);
        return {
          id: node.id,
          kind: "processor" as IngestionNodeKind,
          x: Math.round(node.position.x),
          y: Math.round(node.position.y),
          data: {
            nodeType: form.nodeType,
            settings,
            condition,
            note: null
          }
        };
      }
      return {
        id: node.id,
        kind: node.type as IngestionNodeKind,
        x: Math.round(node.position.x),
        y: Math.round(node.position.y),
        data: {
          nodeType: null,
          settings: null,
          condition: null,
          note: (node.data.note as string) ?? null
        }
      };
    }),
    edges: edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: typeof edge.label === "string" && edge.label.trim() ? edge.label.trim() : null,
      condition: (edge.data?.condition as Record<string, unknown> | undefined) ?? null
    }))
  };
}

/** 图指纹（脏标记） */
export function graphFingerprint(nodes: IngestionFlowNode[], edges: IngestionFlowEdge[]): string {
  const api = toApiGraph(nodes, edges);
  const sortedNodes = [...api.nodes].sort((a, b) => a.id.localeCompare(b.id));
  const sortedEdges = [...api.edges].sort((a, b) => a.id.localeCompare(b.id));
  return JSON.stringify({ nodes: sortedNodes, edges: sortedEdges });
}

/** 连线合法性校验（与后端校验规则一致，提前拦截） */
export function validateConnection(
  connection: Connection,
  nodes: IngestionFlowNode[],
  edges: IngestionFlowEdge[]
): string | null {
  const { source, target } = connection;
  if (!source || !target) {
    return "连线缺少起点或终点";
  }
  if (source === target) {
    return "不能连接到自己";
  }
  const sourceNode = nodes.find((n) => n.id === source);
  const targetNode = nodes.find((n) => n.id === target);
  if (!sourceNode || !targetNode) {
    return "节点不存在";
  }
  if (sourceNode.type === "end") {
    return "结束节点不能有出边";
  }
  if (targetNode.type === "start") {
    return "开始节点不能有入边";
  }
  if (edges.some((e) => e.source === source && e.target === target)) {
    return "这两个节点之间已存在连线";
  }
  if (canReach(target, source, edges)) {
    return "该连线会形成环路";
  }
  return null;
}

function canReach(from: string, to: string, edges: IngestionFlowEdge[]): boolean {
  const adjacency = new Map<string, string[]>();
  for (const edge of edges) {
    const list = adjacency.get(edge.source) ?? [];
    list.push(edge.target);
    adjacency.set(edge.source, list);
  }
  const visited = new Set<string>();
  const stack = [from];
  while (stack.length > 0) {
    const current = stack.pop() as string;
    if (current === to) {
      return true;
    }
    if (visited.has(current)) {
      continue;
    }
    visited.add(current);
    for (const next of adjacency.get(current) ?? []) {
      stack.push(next);
    }
  }
  return false;
}

/**
 * 按模板生成线性图（新建流水线用）
 * <p>start → processor... → end；processor 使用各类型默认配置。</p>
 */
export function buildTemplateGraph(nodeTypes: PipelineNodeType[]): IngestionGraph {
  const nodes: IngestionGraph["nodes"] = [
    { id: "start", kind: "start", x: 0, y: 160, data: {} }
  ];
  const edges: IngestionGraph["edges"] = [];
  let previous = "start";
  nodeTypes.forEach((nodeType, index) => {
    const id = `step_${index + 1}`;
    const form = createNodeForm(nodeType);
    const { settings, condition } = buildGraphNodeData(form);
    nodes.push({
      id,
      kind: "processor",
      x: 220 + index * 240,
      y: 160,
      data: { nodeType, settings, condition }
    });
    edges.push({ id: `e${index + 1}`, source: previous, target: id });
    previous = id;
  });
  nodes.push({
    id: "end",
    kind: "end",
    x: 220 + nodeTypes.length * 240,
    y: 160,
    data: {}
  });
  edges.push({ id: "e-end", source: previous, target: "end" });
  return { version: 1, nodes, edges };
}

/** 缩略图配色 */
export function miniMapNodeColor(node: IngestionFlowNode): string {
  if (node.type === "start") return "#10b981";
  if (node.type === "end") return "#94a3b8";
  if (node.type === "condition") return "#f59e0b";
  const nodeType = (node.data.form as PipelineNodeForm | undefined)?.nodeType;
  switch (nodeType) {
    case "fetcher":
      return "#0ea5e9";
    case "parser":
      return "#8b5cf6";
    case "chunker":
      return "#6366f1";
    case "enhancer":
      return "#ec4899";
    case "enricher":
      return "#14b8a6";
    case "indexer":
      return "#f97316";
    case "graph_extractor":
      return "#84cc16";
    default:
      return "#6366f1";
  }
}

/** 处理器节点副标题 */
export function processorSubtitle(form: PipelineNodeForm): string {
  const type = getNodeTypeShortLabel(form.nodeType);
  return form.nodeId ? `${type} · ${form.nodeId}` : type;
}

export { getNodeTypeShortLabel };
