import { MarkerType, type Connection, type Edge, type Node, type XYPosition } from "@xyflow/react";

import type { WorkflowGraph, WorkflowNodeKind } from "@/services/workflowService";

/** 画布节点数据（与后端 graph node.data 对齐，空串归一为 undefined） */
export interface FlowNodeData extends Record<string, unknown> {
  action?: string;
  tool?: string;
  when?: string;
  expression?: string;
}

export type FlowNode = Node<FlowNodeData, WorkflowNodeKind>;
export type FlowEdge = Edge;

export const NODE_KIND_LABELS: Record<WorkflowNodeKind, string> = {
  start: "开始",
  step: "步骤",
  condition: "条件分支",
  end: "结束"
};

/** 节点尺寸（自动布局与初始摆放用） */
export const NODE_SIZE: Record<WorkflowNodeKind, { width: number; height: number }> = {
  start: { width: 120, height: 44 },
  step: { width: 240, height: 96 },
  condition: { width: 220, height: 84 },
  end: { width: 120, height: 44 }
};

let idSeq = 0;

/** 生成画布内唯一 ID（时间戳+自增，避免依赖 crypto 环境差异） */
export function newId(prefix: string): string {
  idSeq += 1;
  return `${prefix}-${Date.now().toString(36)}${idSeq.toString(36)}`;
}

/** 新建节点的默认数据 */
export function defaultNodeData(kind: WorkflowNodeKind): FlowNodeData {
  switch (kind) {
    case "step":
      return { action: "新步骤：描述这一步要做什么", tool: "", when: "" };
    case "condition":
      return { expression: "判断条件（如：物流是否延误）" };
    default:
      return {};
  }
}

/** 新建节点 */
export function createNode(kind: WorkflowNodeKind, position: XYPosition): FlowNode {
  return {
    id: newId(kind),
    type: kind,
    position,
    data: defaultNodeData(kind)
  };
}

/** 后端图 → React Flow 节点 */
export function toFlowNodes(graph: WorkflowGraph | null | undefined): FlowNode[] {
  if (!graph?.nodes?.length) {
    return [];
  }
  return graph.nodes.map((node) => ({
    id: node.id,
    type: node.kind,
    position: { x: node.x ?? 0, y: node.y ?? 0 },
    data: {
      action: node.data?.action ?? undefined,
      tool: node.data?.tool ?? undefined,
      when: node.data?.when ?? undefined,
      expression: node.data?.expression ?? undefined
    }
  }));
}

/** 后端图 → React Flow 连线 */
export function toFlowEdges(graph: WorkflowGraph | null | undefined): FlowEdge[] {
  if (!graph?.edges?.length) {
    return [];
  }
  return graph.edges.map((edge) => makeEdge(edge.id, edge.source, edge.target, edge.label ?? undefined));
}

/** 构造一条连线（统一箭头与样式） */
export function makeEdge(id: string, source: string, target: string, label?: string): FlowEdge {
  return {
    id,
    source,
    target,
    label: label || undefined,
    type: "smoothstep",
    markerEnd: { type: MarkerType.ArrowClosed, width: 16, height: 16 },
    data: { label: label ?? "" }
  };
}

/** React Flow 节点/连线 → 后端图 */
export function toApiGraph(nodes: FlowNode[], edges: FlowEdge[]): WorkflowGraph {
  return {
    version: 1,
    nodes: nodes.map((node) => ({
      id: node.id,
      kind: node.type as WorkflowNodeKind,
      x: Math.round(node.position.x),
      y: Math.round(node.position.y),
      data: {
        action: node.data.action?.trim() || null,
        tool: node.data.tool?.trim() || null,
        when: node.data.when?.trim() || null,
        expression: node.data.expression?.trim() || null
      }
    })),
    edges: edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: typeof edge.label === "string" && edge.label.trim() ? edge.label.trim() : null
    }))
  };
}

/** 是否有未保存变更（对比后端图的 JSON 快照） */
export function graphFingerprint(nodes: FlowNode[], edges: FlowEdge[]): string {
  const api = toApiGraph(nodes, edges);
  const sortedNodes = [...api.nodes].sort((a, b) => a.id.localeCompare(b.id));
  const sortedEdges = [...api.edges].sort((a, b) => a.id.localeCompare(b.id));
  return JSON.stringify({ nodes: sortedNodes, edges: sortedEdges });
}

/** 该节点类型是否允许有入边/出边（连线校验用） */
export function canHaveIncoming(kind: WorkflowNodeKind): boolean {
  return kind !== "start";
}

export function canHaveOutgoing(kind: WorkflowNodeKind): boolean {
  return kind !== "end";
}

/**
 * 校验一条拟建立的连线；返回错误文案，null 表示允许。
 * 后端保存时会做完整校验，这里只拦截明显错误以提升交互体验。
 */
export function validateConnection(
  connection: Connection,
  nodes: FlowNode[],
  edges: FlowEdge[]
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
  if (!canHaveOutgoing(sourceNode.type as WorkflowNodeKind)) {
    return "结束节点不能有出边";
  }
  if (!canHaveIncoming(targetNode.type as WorkflowNodeKind)) {
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

/** target 是否可达 source（用于阻止成环） */
function canReach(from: string, to: string, edges: FlowEdge[]): boolean {
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

/** 从旧步骤生成画布骨架（后端已返回 graph，此处仅兜底） */
export function emptyGraphNodes(): FlowNode[] {
  return [];
}
