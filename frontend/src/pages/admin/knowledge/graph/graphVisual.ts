import type { GraphData } from "@antv/g6";
import type { GraphSubgraph } from "@/services/graphService";

export const ENTITY_TYPES = ["PERSON", "ORG", "DEPT", "ROLE", "PRODUCT", "PROCESS", "SYSTEM", "DOC", "OTHER"];

export const typeColor = (type: string): string => {
  switch (type) {
    case "PERSON": return "#f59e0b";
    case "ORG": return "#8b5cf6";
    case "DEPT": return "#3b82f6";
    case "ROLE": return "#10b981";
    case "PRODUCT": return "#ec4899";
    case "PROCESS": return "#06b6d4";
    case "SYSTEM": return "#6366f1";
    case "DOC": return "#84cc16";
    default: return "#94a3b8";
  }
};

export interface NodePosition {
  x: number;
  y: number;
}

/** 统计每个节点的度数（入度 + 出度），用于节点大小映射 */
function collectDegrees(subgraph: GraphSubgraph): Map<string, number> {
  const degree = new Map<string, number>();
  for (const link of subgraph.links) {
    degree.set(link.source, (degree.get(link.source) ?? 0) + 1);
    degree.set(link.target, (degree.get(link.target) ?? 0) + 1);
  }
  return degree;
}

/** 节点半径：度数越大越大（log2 平滑，防 hub 节点过大） */
function nodeRadius(degree: number): number {
  return Math.min(22, 8 + Math.log2(degree + 1) * 4);
}

/**
 * 后端子图数据 → G6 GraphData 映射
 *
 * @param subgraph 后端子图
 * @param prevPositions 已存在节点的坐标（聚焦展开时回填，避免位置抖动）
 * @param showEdgeLabels 是否显示边谓词标签
 * @param hiddenTypes 隐藏的实体类型集合
 */
export function buildGraphData(
  subgraph: GraphSubgraph,
  prevPositions: Map<string, NodePosition>,
  showEdgeLabels: boolean,
  hiddenTypes: Set<string>
): GraphData {
  const degree = collectDegrees(subgraph);
  const visibleIds = new Set<string>();
  const nodes = subgraph.nodes
    .filter((n) => !hiddenTypes.has(n.type))
    .map((n) => {
      visibleIds.add(n.id);
      const prev = prevPositions.get(n.id);
      const r = nodeRadius(degree.get(n.id) ?? 0);
      return {
        id: n.id,
        data: { type: n.type, name: n.name },
        style: {
          x: prev?.x,
          y: prev?.y,
          fill: typeColor(n.type),
          r,
          lineWidth: 1,
          stroke: "#ffffff",
          labelText: n.name,
          labelFontSize: 12,
          labelFill: "#94a3b8",
          labelPlacement: "bottom",
          labelOffsetY: 6
        }
      };
    });
  const edges = subgraph.links
    .filter((l) => visibleIds.has(l.source) && visibleIds.has(l.target))
    .map((l, i) => ({
      id: `${l.source}->${l.target}#${i}`,
      source: l.source,
      target: l.target,
      data: { predicate: l.predicate },
      style: {
        stroke: "#94a3b8",
        opacity: 0.7,
        endArrow: true,
        lineWidth: 1,
        labelText: showEdgeLabels ? l.predicate : "",
        labelFontSize: 10,
        labelFill: "#94a3b8",
        labelPlacement: "center"
      }
    }));
  return { nodes, edges };
}