/// <reference lib="webworker" />
import { forceCenter, forceCollide, forceLink, forceManyBody, forceSimulation } from "d3-force";
import type { LayoutEdge, LayoutNode, LayoutRequest, LayoutResponse } from "./layout";

const ctx = self as unknown as DedicatedWorkerGlobalScope;

ctx.onmessage = (event: MessageEvent<LayoutRequest>) => {
  const { nodes, edges, width, height, linkDistance, nodeStrength, iterations } = event.data;
  const simulation = forceSimulation<LayoutNode>(nodes)
    .force(
      "link",
      forceLink<LayoutNode, LayoutEdge>(edges)
        .id((d) => d.id)
        .distance(linkDistance)
    )
    .force("charge", forceManyBody<LayoutNode>().strength(nodeStrength))
    .force("center", forceCenter(width / 2, height / 2))
    .force("collide", forceCollide<LayoutNode>().radius((d) => d.radius + 4))
    .stop();
  simulation.tick(iterations);
  const positions: LayoutResponse["positions"] = {};
  for (const node of simulation.nodes()) {
    positions[node.id] = { x: node.x ?? 0, y: node.y ?? 0 };
  }
  ctx.postMessage({ positions });
};

export {};