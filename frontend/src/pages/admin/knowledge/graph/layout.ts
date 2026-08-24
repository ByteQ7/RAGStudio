import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation
} from "d3-force";
import type { GraphSubgraph } from "@/services/graphService";
import type { NodePosition } from "./graphVisual";

export const LAYOUT_LINK_DISTANCE = 140;
export const LAYOUT_NODE_STRENGTH = -320;
export const LAYOUT_ITERATIONS = 300;
const LAYOUT_COLLIDE_PADDING = 4;
const WORKER_TIMEOUT = 15000;

export interface LayoutNode {
  id: string;
  radius: number;
  fx?: number;
  fy?: number;
}

export interface LayoutEdge {
  source: string;
  target: string;
}

export interface LayoutRequest {
  nodes: LayoutNode[];
  edges: LayoutEdge[];
  width: number;
  height: number;
  linkDistance: number;
  nodeStrength: number;
  iterations: number;
}

export interface LayoutResponse {
  positions: Record<string, NodePosition>;
}

/** 节点半径：与 graphVisual.nodeRadius 保持一致（度数越大越大） */
function nodeRadius(degree: number): number {
  return Math.min(22, 8 + Math.log2(degree + 1) * 4);
}

function collectDegrees(subgraph: GraphSubgraph): Map<string, number> {
  const degree = new Map<string, number>();
  for (const link of subgraph.links) {
    degree.set(link.source, (degree.get(link.source) ?? 0) + 1);
    degree.set(link.target, (degree.get(link.target) ?? 0) + 1);
  }
  return degree;
}

function buildLayoutInput(
  subgraph: GraphSubgraph,
  prevPositions: Map<string, NodePosition>
): { nodes: LayoutNode[]; edges: LayoutEdge[] } {
  const degree = collectDegrees(subgraph);
  const nodes = subgraph.nodes.map((n) => {
    const prev = prevPositions.get(n.id);
    return {
      id: n.id,
      radius: nodeRadius(degree.get(n.id) ?? 0),
      fx: prev?.x,
      fy: prev?.y
    };
  });
  const edges = subgraph.links.map((l) => ({ source: l.source, target: l.target }));
  return { nodes, edges };
}

function runSimulation(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  width: number,
  height: number,
  linkDistance: number,
  nodeStrength: number,
  iterations: number
): Map<string, NodePosition> {
  const simulation = forceSimulation<LayoutNode>(nodes)
    .force(
      "link",
      forceLink<LayoutNode, LayoutEdge>(edges)
        .id((d) => d.id)
        .distance(linkDistance)
    )
    .force("charge", forceManyBody<LayoutNode>().strength(nodeStrength))
    .force("center", forceCenter(width / 2, height / 2))
    .force("collide", forceCollide<LayoutNode>().radius((d) => d.radius + LAYOUT_COLLIDE_PADDING))
    .stop();
  simulation.tick(iterations);
  const positions = new Map<string, NodePosition>();
  for (const node of simulation.nodes()) {
    positions.set(node.id, { x: node.x ?? 0, y: node.y ?? 0 });
  }
  return positions;
}

function layoutInWorker(
  nodes: LayoutNode[],
  edges: LayoutEdge[],
  width: number,
  height: number
): Promise<Map<string, NodePosition>> {
  return new Promise((resolve, reject) => {
    let worker: Worker;
    try {
      worker = new Worker(new URL("./layout.worker.ts", import.meta.url), { type: "module" });
    } catch (error) {
      reject(error);
      return;
    }
    const timer = window.setTimeout(() => {
      worker.terminate();
      reject(new Error("layout worker timeout"));
    }, WORKER_TIMEOUT);
    worker.onerror = (event) => {
      window.clearTimeout(timer);
      worker.terminate();
      reject(event);
    };
    worker.onmessage = (event: MessageEvent<LayoutResponse>) => {
      window.clearTimeout(timer);
      worker.terminate();
      const positions = new Map<string, NodePosition>();
      for (const [id, pos] of Object.entries(event.data.positions)) {
        positions.set(id, pos);
      }
      resolve(positions);
    };
    const request: LayoutRequest = {
      nodes,
      edges,
      width,
      height,
      linkDistance: LAYOUT_LINK_DISTANCE,
      nodeStrength: LAYOUT_NODE_STRENGTH,
      iterations: LAYOUT_ITERATIONS
    };
    worker.postMessage(request);
  });
}

/**
 * 计算力导向布局坐标。
 *
 * 优先在 Web Worker 中执行（不阻塞主线程）；worker 不可用/超时/报错时，
 * 回退到主线程同步执行 d3-force（G6 内置 enableWorker 在 Vite 下无法解析 worker 路径会挂起，故自建 worker）。
 *
 * 传入 prevPositions 的节点会以 fx/fy 固定，仅新节点参与迭代（聚焦展开时旧节点不抖动）。
 */
export async function computeLayout(
  subgraph: GraphSubgraph,
  prevPositions: Map<string, NodePosition>,
  width: number,
  height: number
): Promise<Map<string, NodePosition>> {
  const { nodes, edges } = buildLayoutInput(subgraph, prevPositions);
  try {
    return await layoutInWorker(nodes, edges, width, height);
  } catch (error) {
    console.warn("[graph] layout worker failed, fallback to main thread.", error);
    return runSimulation(nodes, edges, width, height, LAYOUT_LINK_DISTANCE, LAYOUT_NODE_STRENGTH, LAYOUT_ITERATIONS);
  }
}