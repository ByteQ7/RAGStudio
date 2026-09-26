import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import { Graph } from "@antv/g6";
import type { GraphData } from "@antv/g6";
import type { GraphSubgraph } from "@/services/graphService";
import { buildGraphData, type NodePosition } from "./graphVisual";
import { computeLayout } from "./layout";

export interface GraphCanvasHandle {
  zoomIn: () => void;
  zoomOut: () => void;
  fitView: () => void;
  exportPng: () => Promise<void>;
  toggleFullscreen: () => void;
  focusEntity: (id: string) => void;
}

interface GraphCanvasProps {
  data: GraphSubgraph | null;
  hiddenTypes: Set<string>;
  showEdgeLabels: boolean;
  layoutLocked: boolean;
  selectedId: string | null;
  /** 外部定位请求（搜索选中/详情面板跳转）：{ id, ts } 以 ts 区分重复请求 */
  focusTarget: { id: string; ts: number } | null;
  onNodeClick: (id: string) => void;
  onNodeFocus: (id: string) => void;
}

const ZOOM_STEP = 1.25;
const ZOOM_RANGE: [number, number] = [0.2, 3];

/** 收集当前画布中所有节点的坐标（聚焦展开时回填，避免位置抖动） */
function collectPositions(graph: Graph): Map<string, NodePosition> {
  const positions = new Map<string, NodePosition>();
  for (const node of graph.getNodeData()) {
    const style = node.style as { x?: number; y?: number } | undefined;
    if (style && typeof style.x === "number" && typeof style.y === "number") {
      positions.set(node.id, { x: style.x, y: style.y });
    }
  }
  return positions;
}

/** 指针离开画布时清理 hover 高亮（G6 hover-activate 只响应元素级 leave，画布级 leave 会残留状态） */
function clearHoverStates(graph: Graph): void {
  const states: Record<string, string[]> = {};
  const collect = (ids: string[]) => {
    for (const id of ids) {
      const current = graph.getElementState(id);
      const next = current.filter((state) => state !== "active" && state !== "inactive");
      if (next.length !== current.length) {
        states[id] = next;
      }
    }
  };
  collect(graph.getNodeData().map((node) => node.id));
  collect(graph.getEdgeData().map((edge) => edge.id));
  if (Object.keys(states).length > 0) {
    void graph.setElementState(states);
  }
}

/** 同步选中态：清除其他节点的 selected，并在目标节点存在时高亮（节点不存在/画布未渲染时静默跳过） */
function syncSelectedState(graph: Graph, selectedId: string | null): void {
  if (!graph.rendered) {
    return;
  }
  const prevSelected = graph.getElementDataByState("node", "selected");
  for (const node of prevSelected) {
    if (node.id !== selectedId) {
      graph.setElementState(node.id, []);
    }
  }
  if (selectedId && graph.getNodeData().some((node) => node.id === selectedId)) {
    graph.setElementState(selectedId, ["selected"]);
  }
}

/** 新旧数据是否构成「聚焦展开」（新图包含全部旧节点 → 保留视口与坐标） */
function isIncrementalExpand(prev: GraphSubgraph | null, next: GraphSubgraph | null): boolean {
  if (!prev || !next || prev.nodes.length === 0) {
    return false;
  }
  const nextIds = new Set(next.nodes.map((n) => n.id));
  return prev.nodes.every((n) => nextIds.has(n.id));
}

export const GraphCanvas = forwardRef<GraphCanvasHandle, GraphCanvasProps>(function GraphCanvas(props, ref) {
  const containerRef = useRef<HTMLDivElement>(null);
  const graphRef = useRef<Graph | null>(null);
  const propsRef = useRef(props);
  const prevDataRef = useRef<GraphSubgraph | null>(null);
  /** 布局请求序号：数据变化时递增，丢弃过期布局结果，防止竞态 */
  const layoutSeqRef = useRef(0);
  /** 首次布局是否已写入画布：避免未拿到坐标就渲染（autoFit 会缩放到最大导致视图空白） */
  const layoutAppliedRef = useRef(false);
  const focusTsRef = useRef(0);
  propsRef.current = props;

  // ==================== 初始化（仅一次） ====================
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const graph = new Graph({
      container,
      padding: 32,
      zoomRange: ZOOM_RANGE,
      animation: { duration: 200 },
      behaviors: [
        "zoom-canvas",
        "drag-canvas",
        { type: "drag-element", key: "drag-element" },
        { type: "hover-activate", key: "hover-activate", degree: 1, activeState: "active", inactiveState: "inactive" }
      ],
      node: {
        state: {
          selected: {
            lineWidth: 3,
            stroke: "#2563eb",
            halo: true,
            haloStroke: "#2563eb",
            haloStrokeOpacity: 0.35,
            haloLineWidth: 8
          },
          active: {
            halo: true,
            haloStroke: "#f59e0b",
            haloLineWidth: 8,
            haloStrokeOpacity: 0.9
          },
          inactive: {
            opacity: 0.25
          }
        }
      },
      edge: {
        state: {
          active: {
            stroke: "#f59e0b",
            opacity: 1
          },
          inactive: {
            opacity: 0.15
          }
        }
      }
    });
    graphRef.current = graph;

    graph.on("canvas:pointerleave", () => clearHoverStates(graph));
    graph.on("node:click", (e) => {
      const id = (e as { target?: { id?: string } }).target?.id;
      if (id) {
        propsRef.current.onNodeClick(id);
      }
    });
    graph.on("node:dblclick", (e) => {
      const id = (e as { target?: { id?: string } }).target?.id;
      if (id) {
        propsRef.current.onNodeFocus(id);
      }
    });

    return () => {
      graph.destroy();
      graphRef.current = null;
      prevDataRef.current = null;
      layoutAppliedRef.current = false;
    };
  }, []);

  // ==================== 数据更新 ====================
  // 布局计算放在 Web Worker 中执行（不阻塞主线程），完成后一次性渲染终态；
  // 聚焦展开时旧节点以固定坐标（fx/fy）参与迭代，避免整图重排。
  useEffect(() => {
    const graph = graphRef.current;
    const data = props.data;
    if (!graph || !data || data.nodes.length === 0) {
      return;
    }
    const seq = ++layoutSeqRef.current;
    const container = containerRef.current;
    const width = container?.clientWidth ?? 800;
    const height = container?.clientHeight ?? 640;
    const incremental = isIncrementalExpand(prevDataRef.current, data);
    const positions = incremental ? collectPositions(graph) : new Map<string, NodePosition>();
    void computeLayout(data, positions, width, height).then((layoutResult) => {
      if (seq !== layoutSeqRef.current || !graphRef.current) {
        return;
      }
      const gd: GraphData = buildGraphData(
        data,
        layoutResult,
        propsRef.current.showEdgeLabels,
        propsRef.current.hiddenTypes
      );
      graph.setData(gd);
      layoutAppliedRef.current = true;
      void graph
        .render()
        .then(() => {
          if (!incremental) {
            graph.fitView({}, { duration: 300 });
          }
          syncSelectedState(graph, propsRef.current.selectedId);
        })
        .catch(() => {
          // 画布在渲染完成前被销毁（如数据重载触发重新挂载），忽略该渲染结果
        });
    });
    prevDataRef.current = props.data;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.data]);

  // 边标签 / 类型过滤变化：重建数据但保留坐标（独立于数据更新的 effect，避免重复渲染）
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph || !props.data || props.data.nodes.length === 0) {
      return;
    }
    // 首次布局尚未写入时跳过：此时没有坐标，渲染会让 autoFit 以原点包围盒缩放到最大
    if (!layoutAppliedRef.current) {
      return;
    }
    const gd: GraphData = buildGraphData(props.data, collectPositions(graph), props.showEdgeLabels, props.hiddenTypes);
    graph.setData(gd);
    syncSelectedState(graph, propsRef.current.selectedId);
    void graph.render().catch(() => {
      // 同数据更新：画布销毁中断渲染时忽略
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.showEdgeLabels, props.hiddenTypes]);

  // ==================== 选中态 ====================
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) {
      return;
    }
    syncSelectedState(graph, props.selectedId);
  }, [props.selectedId]);

  // ==================== 外部定位请求 ====================
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph || !props.focusTarget) {
      return;
    }
    const { id, ts } = props.focusTarget;
    const lastTs = focusTsRef.current;
    focusTsRef.current = ts;
    if (lastTs === ts) {
      return;
    }
    const exists = graph.getNodeData().some((n) => n.id === id);
    if (!exists || !graph.rendered) {
      return;
    }
    graph.setElementState(id, ["selected"]);
    void graph.focusElement(id, { duration: 300 });
  }, [props.focusTarget]);

  // ==================== 布局锁定 ====================
  useEffect(() => {
    graphRef.current?.updateBehavior({ key: "drag-element", enable: !props.layoutLocked });
  }, [props.layoutLocked]);

  // ==================== 暴露给工具栏的命令 ====================
  useImperativeHandle(ref, () => ({
    zoomIn: () => {
      const graph = graphRef.current;
      if (!graph) return;
      const next = Math.min(graph.getZoom() * ZOOM_STEP, ZOOM_RANGE[1]);
      void graph.zoomTo(next, { duration: 200 });
    },
    zoomOut: () => {
      const graph = graphRef.current;
      if (!graph) return;
      const next = Math.max(graph.getZoom() / ZOOM_STEP, ZOOM_RANGE[0]);
      void graph.zoomTo(next, { duration: 200 });
    },
    fitView: () => {
      void graphRef.current?.fitView({}, { duration: 300 });
    },
    exportPng: async () => {
      const graph = graphRef.current;
      if (!graph) return;
      const url = await graph.getCanvas().toDataURL({ type: "image/png", encoderOptions: 1 });
      const a = document.createElement("a");
      a.href = url;
      a.download = `graph-${Date.now()}.png`;
      a.click();
    },
    toggleFullscreen: () => {
      const el = containerRef.current;
      if (!el) return;
      if (document.fullscreenElement) {
        void document.exitFullscreen();
      } else {
        void el.requestFullscreen();
      }
    },
    focusEntity: (id: string) => {
      const graph = graphRef.current;
      if (!graph || !graph.rendered) return;
      const exists = graph.getNodeData().some((n) => n.id === id);
      if (!exists) {
        return;
      }
      graph.setElementState(id, ["selected"]);
      void graph.focusElement(id, { duration: 300 });
    }
  }));

  return (
    <div
      ref={containerRef}
      className="h-[640px] w-full overflow-hidden rounded-lg border"
      style={{ borderColor: "var(--color-border-secondary)", background: "var(--color-bg-container)" }}
    />
  );
});