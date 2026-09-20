import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import dagre from "@dagrejs/dagre";
import {
  addEdge,
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type OnSelectionChangeParams
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  LayoutGrid,
  Loader2,
  Plus,
  Redo2,
  Save,
  ShieldCheck,
  Undo2
} from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  createWorkflow,
  getWorkflow,
  updateWorkflow,
  validateWorkflowGraph,
  type WorkflowGraph,
  type WorkflowGraphValidation,
  type WorkflowNodeKind
} from "@/services/workflowService";
import { useThemeStore } from "@/stores/themeStore";
import { getErrorMessage } from "@/utils/error";

import {
  createNode,
  graphFingerprint,
  makeEdge,
  NODE_SIZE,
  newId,
  toApiGraph,
  toFlowEdges,
  toFlowNodes,
  validateConnection,
  type FlowEdge,
  type FlowNode,
  type FlowNodeData
} from "./canvas/graphModel";
import { NODE_DND_TYPE, NodePalette } from "./canvas/NodePalette";
import { PropertyPanel } from "./canvas/PropertyPanel";
import { FLOW_NODE_TYPES } from "./canvas/WorkflowNodes";

/** 缩略图节点配色（按节点类型区分） */
function miniMapNodeColor(node: FlowNode): string {
  switch (node.type) {
    case "start":
      return "#10b981";
    case "condition":
      return "#f59e0b";
    case "end":
      return "#94a3b8";
    default:
      return "#6366f1";
  }
}

const HISTORY_LIMIT = 50;

/** 初始画布：开始 → 占位步骤 → 结束，开箱即用；用户可编辑/删除/继续拖入节点编排 */
function blankGraph(): WorkflowGraph {
  return {
    version: 1,
    nodes: [
      { id: "start", kind: "start", x: 80, y: 160, data: {} },
      {
        id: "step-1",
        kind: "step",
        x: 320,
        y: 160,
        data: { action: "新步骤：描述这一步要做什么", tool: null, when: null, expression: null }
      },
      { id: "end", kind: "end", x: 600, y: 160, data: {} }
    ],
    edges: [
      { id: "e-1", source: "start", target: "step-1" },
      { id: "e-2", source: "step-1", target: "end" }
    ]
  };
}

/** 组合状态指纹：图 + 基本信息，统一驱动脏标记 */
function stateFingerprint(
  nodes: FlowNode[],
  edges: FlowEdge[],
  name: string,
  title: string,
  description: string,
  notes: string
): string {
  return JSON.stringify([graphFingerprint(nodes, edges), name, title, description, notes]);
}
/** 属性输入的编辑合并窗口（避免每敲一个字产生一个撤销点） */
const EDIT_COALESCE_MS = 800;

interface Snapshot {
  nodes: FlowNode[];
  edges: FlowEdge[];
}

/** 工作流画布编辑页（独立路由 /admin/workflows/:name/edit） */
export function WorkflowCanvasPage() {
  return (
    <ReactFlowProvider>
      <CanvasInner />
    </ReactFlowProvider>
  );
}

function CanvasInner() {
  const { name = "" } = useParams<{ name: string }>();
  // 新建使用独立路由 /admin/workflows/new（无 :name 参数）；
  // 避免与合法工作流名 "new"（后端 NAME_PATTERN 允许）冲突
  const isCreate = !name;
  const navigate = useNavigate();
  const { screenToFlowPosition } = useReactFlow();
  const theme = useThemeStore((s) => s.theme);

  const [nodes, setNodes, onNodesChange] = useNodesState<FlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<FlowEdge>([]);
  const [wfName, setWfName] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [notes, setNotes] = useState("");
  const [loading, setLoading] = useState(!isCreate);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [validation, setValidation] = useState<WorkflowGraphValidation | null>(null);
  const [selectedNode, setSelectedNode] = useState<FlowNode | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<FlowEdge | null>(null);
  const [leaveOpen, setLeaveOpen] = useState(false);

  const baselineRef = useRef<string>("");
  const historyRef = useRef<{ past: Snapshot[]; future: Snapshot[] }>({ past: [], future: [] });
  const [historyVersion, setHistoryVersion] = useState(0);
  const lastEditAtRef = useRef(0);

  // ==================== 加载 ====================

  useEffect(() => {
    if (isCreate) {
      const graph = blankGraph();
      const flowNodes = toFlowNodes(graph);
      const flowEdges = toFlowEdges(graph);
      setNodes(flowNodes);
      setEdges(flowEdges);
      setWfName("");
      setTitle("");
      setDescription("");
      setNotes("");
      setSelectedNode(null);
      setSelectedEdge(null);
      setValidation(null);
      baselineRef.current = stateFingerprint(flowNodes, flowEdges, "", "", "", "");
      historyRef.current = { past: [], future: [] };
      setHistoryVersion((v) => v + 1);
      setDirty(false);
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    getWorkflow(name)
      .then((detail) => {
        if (!active) return;
        const flowNodes = toFlowNodes(detail.graph);
        const flowEdges = toFlowEdges(detail.graph);
        setNodes(flowNodes);
        setEdges(flowEdges);
        setWfName(detail.name);
        setTitle(detail.title);
        setDescription(detail.description);
        setNotes(detail.notes ?? "");
        setSelectedNode(null);
        setSelectedEdge(null);
        setValidation(null);
        baselineRef.current = stateFingerprint(
            flowNodes, flowEdges, detail.name, detail.title, detail.description, detail.notes ?? "");
        historyRef.current = { past: [], future: [] };
        setHistoryVersion((v) => v + 1);
        setDirty(false);
      })
      .catch((error) => {
        toast.error(getErrorMessage(error));
        navigate("/admin/workflows", { replace: true });
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [name, isCreate, navigate, setEdges, setNodes]);

  // ==================== 脏标记 + 离开确认 ====================

  useEffect(() => {
    if (loading) return;
    setDirty(
      stateFingerprint(nodes, edges, wfName, title, description, notes) !== baselineRef.current
    );
  }, [nodes, edges, wfName, title, description, notes, loading]);

  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

  const handleBack = useCallback(() => {
    if (dirty) {
      setLeaveOpen(true);
    } else {
      navigate("/admin/workflows");
    }
  }, [dirty, navigate]);

  // ==================== 撤销 / 重做 ====================

  const takeSnapshot = useCallback(() => {
    historyRef.current.past.push({
      nodes: nodes.map((n) => ({ ...n, data: { ...n.data } })),
      edges: edges.map((e) => ({ ...e }))
    });
    if (historyRef.current.past.length > HISTORY_LIMIT) {
      historyRef.current.past.shift();
    }
    historyRef.current.future = [];
    setHistoryVersion((v) => v + 1);
  }, [nodes, edges]);

  const undo = useCallback(() => {
    const snapshot = historyRef.current.past.pop();
    if (!snapshot) return;
    historyRef.current.future.push({
      nodes: nodes.map((n) => ({ ...n, data: { ...n.data } })),
      edges: edges.map((e) => ({ ...e }))
    });
    setNodes(snapshot.nodes);
    setEdges(snapshot.edges);
    setHistoryVersion((v) => v + 1);
  }, [nodes, edges, setNodes, setEdges]);

  const redo = useCallback(() => {
    const snapshot = historyRef.current.future.pop();
    if (!snapshot) return;
    historyRef.current.past.push({
      nodes: nodes.map((n) => ({ ...n, data: { ...n.data } })),
      edges: edges.map((e) => ({ ...e }))
    });
    setNodes(snapshot.nodes);
    setEdges(snapshot.edges);
    setHistoryVersion((v) => v + 1);
  }, [nodes, edges, setNodes, setEdges]);

  // historyVersion 用于驱动撤销/重做按钮可用态刷新（historyRef 本身不触发渲染）
  const canUndo = useMemo(() => historyVersion >= 0 && historyRef.current.past.length > 0, [historyVersion]);
  const canRedo = useMemo(() => historyVersion >= 0 && historyRef.current.future.length > 0, [historyVersion]);

  // ==================== 编辑操作 ====================

  const onConnect = useCallback(
    (connection: Connection) => {
      const error = validateConnection(connection, nodes, edges);
      if (error) {
        toast.warning(error);
        return;
      }
      takeSnapshot();
      setEdges((eds) =>
        addEdge(
          makeEdge(newId("e"), connection.source as string, connection.target as string, ""),
          eds
        )
      );
    },
    [nodes, edges, setEdges, takeSnapshot]
  );

  const handleNodesDelete = useCallback(
    (deleted: FlowNode[]) => {
      if (deleted.length > 0) {
        takeSnapshot();
      }
      setSelectedNode(null);
    },
    [takeSnapshot]
  );

  const handleEdgesDelete = useCallback(
    (deleted: FlowEdge[]) => {
      if (deleted.length > 0) {
        takeSnapshot();
      }
      setSelectedEdge(null);
    },
    [takeSnapshot]
  );

  const handleDrop = useCallback(
    (event: React.DragEvent<HTMLDivElement>) => {
      event.preventDefault();
      const kind = event.dataTransfer.getData(NODE_DND_TYPE) as WorkflowNodeKind;
      if (!kind) return;
      if ((kind === "start" || kind === "end") && nodes.some((n) => n.type === kind)) {
        toast.warning(`${kind === "start" ? "开始" : "结束"}节点只能有一个`);
        return;
      }
      takeSnapshot();
      const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
      setNodes((nds) => nds.concat(createNode(kind, position)));
    },
    [nodes, screenToFlowPosition, setNodes, takeSnapshot]
  );

  const handleDragOver = useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }, []);

  const handleNodeDataChange = useCallback(
    (id: string, patch: Partial<FlowNodeData>) => {
      // 连续输入合并为一个撤销点
      const now = Date.now();
      if (now - lastEditAtRef.current > EDIT_COALESCE_MS) {
        takeSnapshot();
      }
      lastEditAtRef.current = now;
      setNodes((nds) =>
        nds.map((node) => (node.id === id ? { ...node, data: { ...node.data, ...patch } } : node))
      );
      setSelectedNode((current) =>
        current && current.id === id ? { ...current, data: { ...current.data, ...patch } } : current
      );
    },
    [setNodes, takeSnapshot]
  );

  const handleEdgeLabelChange = useCallback(
    (id: string, label: string) => {
      const now = Date.now();
      if (now - lastEditAtRef.current > EDIT_COALESCE_MS) {
        takeSnapshot();
      }
      lastEditAtRef.current = now;
      setEdges((eds) =>
        eds.map((edge) =>
          edge.id === id ? { ...edge, label: label || undefined, data: { ...edge.data, label } } : edge
        )
      );
      setSelectedEdge((current) =>
        current && current.id === id ? { ...current, label: label || undefined } : current
      );
    },
    [setEdges, takeSnapshot]
  );

  const handleDeleteNode = useCallback(
    (id: string) => {
      takeSnapshot();
      setNodes((nds) => nds.filter((n) => n.id !== id));
      setEdges((eds) => eds.filter((e) => e.source !== id && e.target !== id));
      setSelectedNode(null);
    },
    [setNodes, setEdges, takeSnapshot]
  );

  const handleDeleteEdge = useCallback(
    (id: string) => {
      takeSnapshot();
      setEdges((eds) => eds.filter((e) => e.id !== id));
      setSelectedEdge(null);
    },
    [setEdges, takeSnapshot]
  );

  const handleSelectionChange = useCallback(
    ({ nodes: selectedNodes, edges: selectedEdges }: OnSelectionChangeParams) => {
      setSelectedNode((selectedNodes[0] as FlowNode | undefined) ?? null);
      setSelectedEdge((selectedEdges[0] as FlowEdge | undefined) ?? null);
    },
    []
  );

  // ==================== 自动布局 ====================

  const autoLayout = useCallback(() => {
    if (nodes.length === 0) return;
    takeSnapshot();
    const layout = new dagre.graphlib.Graph();
    layout.setDefaultEdgeLabel(() => ({}));
    layout.setGraph({ rankdir: "LR", nodesep: 36, ranksep: 88, marginx: 24, marginy: 24 });
    for (const node of nodes) {
      const size = NODE_SIZE[(node.type ?? "step") as WorkflowNodeKind];
      layout.setNode(node.id, { width: size.width, height: size.height });
    }
    for (const edge of edges) {
      layout.setEdge(edge.source, edge.target);
    }
    dagre.layout(layout);
    setNodes((nds) =>
      nds.map((node) => {
        const point = layout.node(node.id) as { x: number; y: number } | undefined;
        if (!point) return node;
        const size = NODE_SIZE[(node.type ?? "step") as WorkflowNodeKind];
        return {
          ...node,
          position: { x: Math.round(point.x - size.width / 2), y: Math.round(point.y - size.height / 2) }
        };
      })
    );
    toast.success("已按流程顺序自动布局");
  }, [nodes, edges, setNodes, takeSnapshot]);

  // ==================== 校验 / 保存 ====================

  const handleValidate = useCallback(async () => {
    try {
      const result = await validateWorkflowGraph(toApiGraph(nodes, edges));
      setValidation(result);
      if (result.errors.length > 0) {
        toast.error(`校验未通过：${result.errors.length} 个错误`);
      } else if (result.warnings.length > 0) {
        toast.warning(`校验通过，但有 ${result.warnings.length} 条提示`);
      } else {
        toast.success("校验通过");
      }
    } catch (error) {
      toast.error(getErrorMessage(error));
    }
  }, [nodes, edges]);

  const handleSave = useCallback(async () => {
    if (!title.trim() || !description.trim() || (isCreate && !wfName.trim())) {
      toast.error(isCreate ? "请填写标识、标题与描述" : "请填写标题与描述");
      return;
    }
    setSaving(true);
    try {
      const graph = toApiGraph(nodes, edges);
      if (isCreate) {
        const created = await createWorkflow({
          name: wfName.trim(),
          title: title.trim(),
          description: description.trim(),
          notes: notes.trim() || null,
          graph
        });
        toast.success("工作流已创建，可继续在画布上调整");
        navigate(`/admin/workflows/${encodeURIComponent(created.name)}/edit`, { replace: true });
        return;
      }
      await updateWorkflow(name, {
        title: title.trim(),
        description: description.trim(),
        notes: notes.trim() || null,
        graph,
        changeLog: "画布编辑"
      });
      baselineRef.current = stateFingerprint(nodes, edges, wfName, title, description, notes);
      setDirty(false);
      setValidation(null);
      toast.success("工作流已保存（已按画布编译执行步骤）");
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setSaving(false);
    }
  }, [name, isCreate, wfName, title, description, notes, nodes, edges, navigate]);

  // ==================== 渲染 ====================

  const disabledKinds = useMemo(() => {
    const kinds = new Set<WorkflowNodeKind>();
    if (nodes.some((n) => n.type === "start")) kinds.add("start");
    if (nodes.some((n) => n.type === "end")) kinds.add("end");
    return kinds;
  }, [nodes]);

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="mr-2 h-5 w-5 animate-spin text-indigo-500" />
        <span className="text-sm text-[var(--color-text-secondary)]">加载工作流…</span>
      </div>
    );
  }

  return (
    <div className="flex flex-1 min-h-0 flex-col bg-[var(--color-bg-layout)]">
      {/* 工具条 */}
      <div className="flex shrink-0 items-center gap-2 border-b border-[var(--color-border-secondary)] bg-[var(--color-bg-container)] px-3 py-2">
        <Button variant="ghost" size="sm" onClick={handleBack}>
          <ArrowLeft className="mr-1 h-4 w-4" />
          返回
        </Button>
        {isCreate ? (
          <p className="text-sm font-semibold text-[var(--color-text)]">
            新建工作流
            {dirty ? <span className="ml-1.5 text-xs font-normal text-amber-600">● 未保存</span> : null}
          </p>
        ) : (
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-[var(--color-text)]">
              {title || name}
              {dirty ? <span className="ml-1.5 text-xs font-normal text-amber-600">● 未保存</span> : null}
            </p>
            <p className="truncate font-mono text-[11px] text-[var(--color-text-tertiary)]">{name}</p>
          </div>
        )}
        <div className="ml-auto flex items-center gap-1.5">
          <Button variant="outline" size="sm" onClick={undo} disabled={!canUndo} title="撤销">
            <Undo2 className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="sm" onClick={redo} disabled={!canRedo} title="重做">
            <Redo2 className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="sm" onClick={autoLayout} title="按流程顺序自动布局">
            <LayoutGrid className="mr-1 h-4 w-4" />
            自动布局
          </Button>
          <Button variant="outline" size="sm" onClick={() => void handleValidate()}>
            <ShieldCheck className="mr-1 h-4 w-4" />
            校验
          </Button>
          <Button size="sm" onClick={() => void handleSave()} disabled={saving}>
            {saving ? (
              <Loader2 className="mr-1 h-4 w-4 animate-spin" />
            ) : isCreate ? (
              <Plus className="mr-1 h-4 w-4" />
            ) : (
              <Save className="mr-1 h-4 w-4" />
            )}
            {isCreate ? "创建工作流" : "保存"}
          </Button>
        </div>
      </div>

      {/* 基本信息栏：画布内直接编辑（新建含标识，编辑模式标识只读） */}
      <div className="flex shrink-0 flex-wrap items-center gap-2 border-b border-[var(--color-border-secondary)] bg-[var(--color-bg-container)] px-3 py-2">
        {isCreate ? (
          <Input
            value={wfName}
            onChange={(e) => setWfName(e.target.value)}
            placeholder="标识（kebab-case，如 refund-dispute）"
            className="h-8 w-[240px] font-mono text-xs"
            maxLength={64}
          />
        ) : (
          <span className="h-8 w-[240px] shrink-0 truncate rounded-md bg-[var(--color-fill-tertiary)] px-2 py-1.5 font-mono text-xs leading-5 text-[var(--color-text-tertiary)]">
            {wfName}
          </span>
        )}
        <Input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="标题（必填，如：退款争议处理）"
          className="h-8 w-[200px]"
          maxLength={128}
        />
        <Input
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="描述（必填：什么问题/何时使用）"
          className="h-8 min-w-[160px] flex-1"
          maxLength={1024}
        />
        <Input
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="说明（可选：前置条件/边界）"
          className="h-8 min-w-[140px] flex-1"
          maxLength={2000}
        />
      </div>

      {/* 画布区 */}
      <div className="flex min-h-0 flex-1">
        <NodePalette disabledKinds={disabledKinds} />
        <div className="relative min-w-0 flex-1" onDrop={handleDrop} onDragOver={handleDragOver}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={FLOW_NODE_TYPES}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodesDelete={handleNodesDelete}
            onEdgesDelete={handleEdgesDelete}
            onSelectionChange={handleSelectionChange}
            fitView
            minZoom={0.2}
            maxZoom={2}
            colorMode={theme}
            proOptions={{ hideAttribution: true }}
            deleteKeyCode={["Backspace", "Delete"]}
            className="bg-[var(--color-bg-layout)]"
          >
            <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
            <Controls showInteractive={false} />
            <MiniMap
              pannable
              zoomable
              className="!bg-[var(--color-bg-container)]"
              maskColor="rgba(0,0,0,0.12)"
              nodeColor={miniMapNodeColor}
              nodeStrokeWidth={2}
            />
          </ReactFlow>
        </div>
        <PropertyPanel
          node={selectedNode}
          edge={selectedEdge}
          onNodeDataChange={handleNodeDataChange}
          onEdgeLabelChange={handleEdgeLabelChange}
          onDeleteNode={handleDeleteNode}
          onDeleteEdge={handleDeleteEdge}
        />
      </div>

      {/* 状态栏 */}
      <div className="flex shrink-0 items-center gap-3 border-t border-[var(--color-border-secondary)] bg-[var(--color-bg-container)] px-3 py-1.5 text-[11px] text-[var(--color-text-tertiary)]">
        <span>{nodes.length} 个节点</span>
        <span>{edges.length} 条连线</span>
        {validation ? (
          validation.errors.length > 0 ? (
            <span className="inline-flex items-center gap-1 text-rose-600">
              <AlertCircle className="h-3.5 w-3.5" />
              {validation.errors[0]}
              {validation.errors.length > 1 ? `（共 ${validation.errors.length} 条错误）` : ""}
            </span>
          ) : validation.warnings.length > 0 ? (
            <span className="inline-flex items-center gap-1 text-amber-600">
              <AlertTriangle className="h-3.5 w-3.5" />
              {validation.warnings[0]}
            </span>
          ) : (
            <span className="inline-flex items-center gap-1 text-emerald-600">
              <CheckCircle2 className="h-3.5 w-3.5" />
              校验通过
            </span>
          )
        ) : null}
        <span className="ml-auto hidden md:inline">
          拖拽左侧节点到画布 · 拖动节点圆点连线 · 选中后右侧编辑 · Delete 删除
        </span>
      </div>

      {/* 未保存离开确认 */}
      <AlertDialog open={leaveOpen} onOpenChange={setLeaveOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>放弃未保存的修改？</AlertDialogTitle>
            <AlertDialogDescription>
              画布上有未保存的变更，离开后将丢失。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>继续编辑</AlertDialogCancel>
            <AlertDialogAction onClick={() => navigate("/admin/workflows")}>
              放弃并离开
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
