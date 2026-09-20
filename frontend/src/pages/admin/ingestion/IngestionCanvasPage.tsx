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
import { cn } from "@/lib/utils";
import { listModels, type AiModel } from "@/services/aiModelConfigService";
import {
  createIngestionPipeline,
  getIngestionPipeline,
  updateIngestionPipeline,
  validateIngestionPipelineGraph,
  type IngestionGraphValidation,
  type IngestionNodeKind
} from "@/services/ingestionService";
import { useThemeStore } from "@/stores/themeStore";
import { getErrorMessage } from "@/utils/error";

import {
  PIPELINE_TEMPLATES,
  type PipelineNodeForm
} from "./pipeline/pipelineFormModel";
import { IngestionNodePalette, PIPELINE_NODE_DND_TYPE } from "./canvas/IngestionNodePalette";
import { IngestionPropertyPanel } from "./canvas/IngestionPropertyPanel";
import {
  buildTemplateGraph,
  createConditionNode,
  createProcessorNode,
  graphFingerprint,
  makeEdge,
  miniMapNodeColor,
  NODE_SIZE,
  newId,
  toApiGraph,
  toFlowEdges,
  toFlowNodes,
  validateConnection,
  type IngestionFlowEdge,
  type IngestionFlowNode
} from "./canvas/pipelineGraphModel";
import { PIPELINE_NODE_TYPES } from "./canvas/PipelineNodes";

const HISTORY_LIMIT = 50;

/** 组合状态指纹：图 + 基本信息（名称/描述），统一驱动脏标记 */
function stateFingerprint(
  nodes: IngestionFlowNode[],
  edges: IngestionFlowEdge[],
  name: string,
  description: string
): string {
  return JSON.stringify([graphFingerprint(nodes, edges), name, description]);
}
const EDIT_COALESCE_MS = 800;

interface Snapshot {
  nodes: IngestionFlowNode[];
  edges: IngestionFlowEdge[];
}

/** 流水线画布编辑页（独立路由 /admin/ingestion/pipelines/:id/edit） */
export function IngestionCanvasPage() {
  return (
    <ReactFlowProvider>
      <CanvasInner />
    </ReactFlowProvider>
  );
}

function CanvasInner() {
  const { id = "" } = useParams<{ id: string }>();
  // 新建使用独立路由 /admin/ingestion/pipelines/new（无 :id 参数）
  const isCreate = !id;
  const navigate = useNavigate();
  const { screenToFlowPosition } = useReactFlow();
  const theme = useThemeStore((s) => s.theme);

  const [nodes, setNodes, onNodesChange] = useNodesState<IngestionFlowNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<IngestionFlowEdge>([]);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [loading, setLoading] = useState(!isCreate);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [validation, setValidation] = useState<IngestionGraphValidation | null>(null);
  const [selectedNode, setSelectedNode] = useState<IngestionFlowNode | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<IngestionFlowEdge | null>(null);
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [models, setModels] = useState<AiModel[]>([]);
  const [templateIndex, setTemplateIndex] = useState(0);

  const baselineRef = useRef("");
  const historyRef = useRef<{ past: Snapshot[]; future: Snapshot[] }>({ past: [], future: [] });
  const [historyVersion, setHistoryVersion] = useState(0);
  const lastEditAtRef = useRef(0);

  useEffect(() => {
    listModels("EMBEDDING").then(setModels).catch(() => {});
  }, []);

  // ==================== 加载 ====================

  useEffect(() => {
    if (isCreate) {
      const graph = buildTemplateGraph(PIPELINE_TEMPLATES[0].nodeTypes);
      const flowNodes = toFlowNodes(graph);
      const flowEdges = toFlowEdges(graph);
      setNodes(flowNodes);
      setEdges(flowEdges);
      setName("");
      setDescription("");
      setTemplateIndex(0);
      baselineRef.current = stateFingerprint(flowNodes, flowEdges, "", "");
      historyRef.current = { past: [], future: [] };
      setHistoryVersion((v) => v + 1);
      setDirty(false);
      setLoading(false);
      return;
    }
    let active = true;
    setLoading(true);
    getIngestionPipeline(id)
      .then((pipeline) => {
        if (!active) return;
        const flowNodes = toFlowNodes(pipeline.graph);
        const flowEdges = toFlowEdges(pipeline.graph);
        setNodes(flowNodes);
        setEdges(flowEdges);
        setName(pipeline.name);
        setDescription(pipeline.description ?? "");
        baselineRef.current = stateFingerprint(
          flowNodes, flowEdges, pipeline.name, pipeline.description ?? "");
        historyRef.current = { past: [], future: [] };
        setHistoryVersion((v) => v + 1);
        setDirty(false);
      })
      .catch((error) => {
        toast.error(getErrorMessage(error));
        navigate("/admin/ingestion?tab=pipelines", { replace: true });
      })
      .finally(() => active && setLoading(false));
    return () => {
      active = false;
    };
  }, [id, isCreate, navigate, setEdges, setNodes]);

  // ==================== 脏标记 ====================

  useEffect(() => {
    if (loading) return;
    setDirty(stateFingerprint(nodes, edges, name, description) !== baselineRef.current);
  }, [nodes, edges, name, description, loading]);

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
      navigate("/admin/ingestion?tab=pipelines");
    }
  }, [dirty, navigate]);

  const goToList = useCallback(() => {
    navigate("/admin/ingestion?tab=pipelines");
  }, [navigate]);

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

  /** 新建模式：套用模板（可撤销） */
  const applyTemplate = useCallback(
    (index: number) => {
      const template = PIPELINE_TEMPLATES[index];
      if (!template) return;
      takeSnapshot();
      const graph = buildTemplateGraph(template.nodeTypes);
      setNodes(toFlowNodes(graph));
      setEdges(toFlowEdges(graph));
      setTemplateIndex(index);
      setSelectedNode(null);
      setSelectedEdge(null);
      setValidation(null);
    },
    [setEdges, setNodes, takeSnapshot]
  );

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

  const canUndo = useMemo(
    () => historyVersion >= 0 && historyRef.current.past.length > 0,
    [historyVersion]
  );
  const canRedo = useMemo(
    () => historyVersion >= 0 && historyRef.current.future.length > 0,
    [historyVersion]
  );

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
        addEdge(makeEdge(newId("e"), connection.source as string, connection.target as string), eds)
      );
    },
    [nodes, edges, setEdges, takeSnapshot]
  );

  const handleNodesDelete = useCallback(
    (deleted: IngestionFlowNode[]) => {
      if (deleted.length > 0) {
        takeSnapshot();
      }
      setSelectedNode(null);
    },
    [takeSnapshot]
  );

  const handleEdgesDelete = useCallback(
    (deleted: IngestionFlowEdge[]) => {
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
      const payload = event.dataTransfer.getData(PIPELINE_NODE_DND_TYPE);
      if (!payload) return;
      takeSnapshot();
      const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
      if (payload === "condition") {
        setNodes((nds) => nds.concat(createConditionNode(position)));
        return;
      }
      if (payload.startsWith("processor:")) {
        const nodeType = payload.slice("processor:".length) as PipelineNodeForm["nodeType"];
        setNodes((nds) => nds.concat(createProcessorNode(nodeType, position)));
      }
    },
    [screenToFlowPosition, setNodes, takeSnapshot]
  );

  const handleDragOver = useCallback((event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
  }, []);

  const handleNodeFormChange = useCallback(
    (nodeId: string, patch: Partial<PipelineNodeForm>) => {
      const now = Date.now();
      if (now - lastEditAtRef.current > EDIT_COALESCE_MS) {
        takeSnapshot();
      }
      lastEditAtRef.current = now;
      setNodes((nds) =>
        nds.map((node) => {
          if (node.id !== nodeId || node.type !== "processor" || !node.data.form) return node;
          const form = { ...(node.data.form as PipelineNodeForm), ...patch };
          return { ...node, data: { ...node.data, form } };
        })
      );
      setSelectedNode((current) => {
        if (!current || current.id !== nodeId || !current.data.form) return current;
        return { ...current, data: { ...current.data, form: { ...(current.data.form as PipelineNodeForm), ...patch } } };
      });
    },
    [setNodes, takeSnapshot]
  );

  const handleNodeNoteChange = useCallback(
    (nodeId: string, note: string) => {
      takeSnapshot();
      setNodes((nds) =>
        nds.map((node) => (node.id === nodeId ? { ...node, data: { ...node.data, note } } : node))
      );
      setSelectedNode((current) =>
        current && current.id === nodeId ? { ...current, data: { ...current.data, note } } : current
      );
    },
    [setNodes, takeSnapshot]
  );

  const handleEdgeChange = useCallback(
    (edgeId: string, patch: { label?: string; condition?: Record<string, unknown> | null }) => {
      const now = Date.now();
      if (now - lastEditAtRef.current > EDIT_COALESCE_MS) {
        takeSnapshot();
      }
      lastEditAtRef.current = now;
      setEdges((eds) =>
        eds.map((edge) => {
          if (edge.id !== edgeId) return edge;
          const nextCondition =
            patch.condition !== undefined
              ? patch.condition
              : ((edge.data?.condition as Record<string, unknown> | null) ?? null);
          return {
            ...edge,
            label: patch.label !== undefined ? patch.label || undefined : edge.label,
            data: { ...edge.data, condition: nextCondition }
          };
        })
      );
      setSelectedEdge((current) => {
        if (!current || current.id !== edgeId) return current;
        const nextCondition =
          patch.condition !== undefined
            ? patch.condition
            : ((current.data?.condition as Record<string, unknown> | null) ?? null);
        return {
          ...current,
          label: patch.label !== undefined ? patch.label || undefined : current.label,
          data: { ...current.data, condition: nextCondition }
        };
      });
    },
    [setEdges, takeSnapshot]
  );

  const handleDeleteNode = useCallback(
    (nodeId: string) => {
      takeSnapshot();
      setNodes((nds) => nds.filter((n) => n.id !== nodeId));
      setEdges((eds) => eds.filter((e) => e.source !== nodeId && e.target !== nodeId));
      setSelectedNode(null);
    },
    [setNodes, setEdges, takeSnapshot]
  );

  const handleDeleteEdge = useCallback(
    (edgeId: string) => {
      takeSnapshot();
      setEdges((eds) => eds.filter((e) => e.id !== edgeId));
      setSelectedEdge(null);
    },
    [setEdges, takeSnapshot]
  );

  const handleSelectionChange = useCallback(
    ({ nodes: selectedNodes, edges: selectedEdges }: OnSelectionChangeParams) => {
      setSelectedNode((selectedNodes[0] as IngestionFlowNode | undefined) ?? null);
      setSelectedEdge((selectedEdges[0] as IngestionFlowEdge | undefined) ?? null);
    },
    []
  );

  // ==================== 自动布局 ====================

  const autoLayout = useCallback(() => {
    if (nodes.length === 0) return;
    takeSnapshot();
    const layout = new dagre.graphlib.Graph();
    layout.setDefaultEdgeLabel(() => ({}));
    layout.setGraph({ rankdir: "LR", nodesep: 36, ranksep: 96, marginx: 24, marginy: 24 });
    for (const node of nodes) {
      const size = NODE_SIZE[(node.type ?? "processor") as IngestionNodeKind];
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
        const size = NODE_SIZE[(node.type ?? "processor") as IngestionNodeKind];
        return {
          ...node,
          position: {
            x: Math.round(point.x - size.width / 2),
            y: Math.round(point.y - size.height / 2)
          }
        };
      })
    );
    toast.success("已按处理顺序自动布局");
  }, [nodes, edges, setNodes, takeSnapshot]);

  // ==================== 校验 / 保存 ====================

  const handleValidate = useCallback(async () => {
    try {
      const result = await validateIngestionPipelineGraph(toApiGraph(nodes, edges));
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
    if (!name.trim()) {
      toast.error("请填写流水线名称");
      return;
    }
    setSaving(true);
    try {
      const graph = toApiGraph(nodes, edges);
      if (isCreate) {
        const created = await createIngestionPipeline({
          name: name.trim(),
          description: description.trim() || null,
          graph
        });
        toast.success("流水线已创建，可继续在画布上调整");
        // 切换到编辑路由（replace，避免返回时回到新建页）
        navigate(`/admin/ingestion/pipelines/${created.id}/edit`, { replace: true });
        return;
      }
      await updateIngestionPipeline(id, {
        name: name.trim(),
        description: description.trim() || null,
        graph
      });
      baselineRef.current = stateFingerprint(nodes, edges, name, description);
      setDirty(false);
      setValidation(null);
      toast.success("流水线已保存（已按画布编译执行节点与分支）");
    } catch (error) {
      toast.error(getErrorMessage(error));
    } finally {
      setSaving(false);
    }
  }, [id, isCreate, name, description, nodes, edges, navigate]);

  // ==================== 渲染 ====================

  const embeddingModels = useMemo(
    () =>
      models.map((model) => ({
        id: model.id,
        modelId: model.modelId,
        modelName: model.modelName,
        isDefault: Boolean(model.isDefault)
      })),
    [models]
  );

  if (loading) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <Loader2 className="mr-2 h-5 w-5 animate-spin text-indigo-500" />
        <span className="text-sm text-[var(--color-text-secondary)]">加载流水线…</span>
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
          <div className="flex min-w-0 flex-1 items-center gap-2">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="流水线名称（必填）"
              className="h-8 w-[220px]"
              maxLength={60}
            />
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="描述（可选）"
              className="h-8 min-w-0 flex-1"
            />
          </div>
        ) : (
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-[var(--color-text)]">
              {name || "流水线"}
              {dirty ? <span className="ml-1.5 text-xs font-normal text-amber-600">● 未保存</span> : null}
            </p>
            <p className="truncate font-mono text-[11px] text-[var(--color-text-tertiary)]">{id}</p>
          </div>
        )}
        <div className="ml-auto flex items-center gap-1.5">
          <Button variant="outline" size="sm" onClick={undo} disabled={!canUndo} title="撤销">
            <Undo2 className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="sm" onClick={redo} disabled={!canRedo} title="重做">
            <Redo2 className="h-4 w-4" />
          </Button>
          <Button variant="outline" size="sm" onClick={autoLayout} title="按处理顺序自动布局">
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
            {isCreate ? "创建流水线" : "保存"}
          </Button>
        </div>
      </div>

      {/* 模板选择（仅新建模式） */}
      {isCreate ? (
        <div className="flex shrink-0 items-center gap-2 overflow-x-auto border-b border-[var(--color-border-secondary)] bg-[var(--color-bg-container)] px-3 py-2">
          <span className="shrink-0 text-xs text-[var(--color-text-tertiary)]">从模板开始：</span>
          {PIPELINE_TEMPLATES.map((template, index) => (
            <button
              key={template.name}
              type="button"
              title={template.description}
              onClick={() => applyTemplate(index)}
              className={cn(
                "shrink-0 rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                templateIndex === index
                  ? "border-indigo-300 bg-indigo-50 text-indigo-700 dark:border-indigo-700 dark:bg-indigo-950/40 dark:text-indigo-300"
                  : "border-[var(--color-border)] text-[var(--color-text-secondary)] hover:border-indigo-200 hover:text-indigo-600 dark:hover:border-indigo-800"
              )}
            >
              {template.name}
            </button>
          ))}
          <span className="ml-auto shrink-0 text-[11px] text-[var(--color-text-tertiary)]">
            模板会覆盖当前画布（可撤销）
          </span>
        </div>
      ) : null}

      {/* 画布区 */}
      <div className="flex min-h-0 flex-1">
        <IngestionNodePalette />
        <div className="relative min-w-0 flex-1" onDrop={handleDrop} onDragOver={handleDragOver}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={PIPELINE_NODE_TYPES}
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
        <IngestionPropertyPanel
          node={selectedNode}
          edge={selectedEdge}
          embeddingModels={embeddingModels}
          onNodeFormChange={handleNodeFormChange}
          onNodeNoteChange={handleNodeNoteChange}
          onEdgeChange={handleEdgeChange}
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
            <AlertDialogDescription>画布上有未保存的变更，离开后将丢失。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>继续编辑</AlertDialogCancel>
            <AlertDialogAction onClick={goToList}>放弃并离开</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
