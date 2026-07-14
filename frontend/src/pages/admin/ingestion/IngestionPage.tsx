import { Fragment, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import {
  ChevronDown,
  ChevronUp,
  ClipboardList,
  FolderKanban,
  Pencil,
  Plus,
  RefreshCw,
  Trash2
} from "lucide-react";
import { toast } from "sonner";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import * as z from "zod";

import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Checkbox } from "@/components/ui/checkbox";
import { Textarea } from "@/components/ui/textarea";
import type {
  IngestionPipeline,
  IngestionPipelineNode,
  IngestionPipelinePayload,
  IngestionTask,
  IngestionTaskNode,
  PageResult
} from "@/services/ingestionService";
import {
  createIngestionPipeline,
  deleteIngestionPipeline,
  getIngestionPipeline,
  getIngestionPipelines,
  getIngestionTask,
  getIngestionTaskNodes,
  getIngestionTasks,
  updateIngestionPipeline,
} from "@/services/ingestionService";
import { getErrorMessage } from "@/utils/error";
const PIPELINE_PAGE_SIZE = 10;
const TASK_PAGE_SIZE = 10;

const STATUS_OPTIONS = [
  { value: "pending", label: "pending" },
  { value: "running", label: "running" },
  { value: "completed", label: "completed" },
  { value: "failed", label: "failed" }
];

const NODE_TYPE_OPTIONS = [
  { value: "fetcher", label: "从数据源获取文档原始字节流" },
  { value: "parser", label: "将原始字节解析为结构化文本" },
  { value: "chunker", label: "将文本按策略切分为 Chunk" },
  { value: "enricher", label: "对每个 Chunk 进行 AI 元数据富化" },
  { value: "enhancer", label: "整个文档级别的 AI 增强处理" },
  { value: "indexer", label: "将 Chunk 向量化并写入向量库" }
];

const getNodeTypeLabel = (type: string): string => {
  const option = NODE_TYPE_OPTIONS.find((o) => o.value === type);
  return option?.label ?? type;
};

const CHUNK_STRATEGY_OPTIONS = [
  { value: "fixed_size", label: "固定大小" },
  { value: "structure_aware", label: "语义感知（推荐）" }
];

const ENHANCER_TASK_OPTIONS = [
  { value: "context_enhance", label: "上下文增强" },
  { value: "keywords", label: "关键词提取" },
  { value: "questions", label: "问题生成" },
  { value: "metadata", label: "元数据生成" }
];

const ENRICHER_TASK_OPTIONS = [
  { value: "keywords", label: "关键词提取" },
  { value: "summary", label: "摘要生成" },
  { value: "metadata", label: "元数据富化" }
];

const NODE_TYPE_SHORT_LABEL: Record<string, string> = {
  fetcher: "获取文档",
  parser: "解析文本",
  chunker: "文本分块",
  enricher: "Chunk 富化",
  enhancer: "文档增强",
  indexer: "向量入库"
};

const getNodeTypeShortLabel = (type: string): string => NODE_TYPE_SHORT_LABEL[type] ?? type;

interface PipelineTemplate {
  name: string;
  description: string;
  nodeTypes: PipelineNodeType[];
}

const PIPELINE_TEMPLATES: PipelineTemplate[] = [
  {
    name: "标准流水线",
    description: "获取 → 解析 → 分块 → Chunk富化 → 向量入库，适合大多数场景",
    nodeTypes: ["fetcher", "parser", "chunker", "enricher", "indexer"]
  },
  {
    name: "简洁流水线",
    description: "获取 → 解析 → 分块 → 向量入库，跳过 AI 富化，处理速度更快",
    nodeTypes: ["fetcher", "parser", "chunker", "indexer"]
  },
  {
    name: "深度处理流水线",
    description: "获取 → 解析 → 文档增强 → 分块 → Chunk富化 → 向量入库，最高质量",
    nodeTypes: ["fetcher", "parser", "enhancer", "chunker", "enricher", "indexer"]
  }
];

const CONDITION_FIELDS = [
  { value: "source_type", label: "来源类型" },
  { value: "file_name", label: "文件名" },
  { value: "mime_type", label: "MIME 类型" }
];

const CONDITION_OPERATORS = [
  { value: "eq", label: "等于" },
  { value: "neq", label: "不等于" },
  { value: "contains", label: "包含" }
];

const PARSER_MIME_OPTIONS = [
  { value: "application/pdf", label: "PDF" },
  { value: "text/markdown", label: "Markdown" },
  { value: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", label: "Word" },
  { value: "text/plain", label: "Plain Text" },
  { value: "text/html", label: "HTML" }
];

const formatDate = (value?: string | null) => {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN");
};

const stringifyJson = (value: unknown) => {
  if (!value) return "-";
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
};

const truncateJson = (value: unknown, max = 120) => {
  const raw = stringifyJson(value);
  if (raw.length <= max) return raw;
  return `${raw.slice(0, max)}...`;
};

const statusBadgeVariant = (status?: string | null) => {
  if (!status) return "outline";
  const normalized = status.toLowerCase();
  if (normalized === "completed") return "default";
  if (normalized === "failed") return "destructive";
  if (normalized === "running") return "secondary";
  return "outline";
};

const nodeStatusVariant = (status?: string | null) => {
  if (!status) return "outline";
  const normalized = status.toLowerCase();
  if (normalized === "success") return "default";
  if (normalized === "failed") return "destructive";
  return "secondary";
};

const pipelineSchema = z.object({
  name: z.string().min(1, "请输入流水线名称").max(60, "名称不能超过60个字符"),
  description: z.string().optional()
});

type PipelineFormValues = z.infer<typeof pipelineSchema>;

type PipelineNodeType =
  | "fetcher"
  | "parser"
  | "enhancer"
  | "chunker"
  | "enricher"
  | "indexer";

interface EnhancerTaskForm {
  id: string;
  type: string;
  enabled: boolean;
  systemPrompt: string;
  userPromptTemplate: string;
}

interface PipelineNodeForm {
  id: string;
  nodeId: string;
  nodeType: PipelineNodeType;
  nextNodeId: string;
  condition: string;
  chunker: {
    strategy: string;
    chunkSize: string;
    overlapSize: string;
    separator: string;
  };
  enhancer: {
    modelId: string;
    tasks: EnhancerTaskForm[];
  };
  enricher: {
    modelId: string;
    attachDocumentMetadata: boolean;
    tasks: EnhancerTaskForm[];
  };
  parser: {
    rulesJson: string;
  };
  indexer: {
    embeddingModel: string;
    metadataFields: string;
  };
  /** 保留原始 settings 中前端不处理的字段，编辑往返时原样写回 */
  _rawSettings?: Record<string, unknown>;
}

export function IngestionPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get("tab");
  const [activeTab, setActiveTab] = useState<"pipelines" | "tasks">(() =>
    tabParam === "tasks" ? "tasks" : "pipelines"
  );
  const [pipelinePage, setPipelinePage] = useState<PageResult<IngestionPipeline> | null>(null);
  const [pipelineKeyword, setPipelineKeyword] = useState("");
  const [pipelineSearch, setPipelineSearch] = useState("");
  const [pipelinePageNo, setPipelinePageNo] = useState(1);
  const [pipelineLoading, setPipelineLoading] = useState(false);
  const [pipelineDialog, setPipelineDialog] = useState<{
    open: boolean;
    mode: "create" | "edit";
    pipeline: IngestionPipeline | null;
  }>({ open: false, mode: "create", pipeline: null });
  const [pipelineNodesDialog, setPipelineNodesDialog] = useState<{
    open: boolean;
    pipeline: IngestionPipeline | null;
  }>({ open: false, pipeline: null });
  const [pipelineDeleteTarget, setPipelineDeleteTarget] = useState<IngestionPipeline | null>(null);

  const [pipelineOptions, setPipelineOptions] = useState<IngestionPipeline[]>([]);

  const [taskPage, setTaskPage] = useState<PageResult<IngestionTask> | null>(null);
  const [taskStatus, setTaskStatus] = useState<string | undefined>();
  const [taskPageNo, setTaskPageNo] = useState(1);
  const [taskLoading, setTaskLoading] = useState(false);
  const [taskDetail, setTaskDetail] = useState<{ open: boolean; taskId: string | null }>({
    open: false,
    taskId: null
  });

  const pipelines = pipelinePage?.records || [];
  const tasks = taskPage?.records || [];

  const loadPipelines = async (pageNo = pipelinePageNo, keyword = pipelineKeyword) => {
    setPipelineLoading(true);
    try {
      const data = await getIngestionPipelines(pageNo, PIPELINE_PAGE_SIZE, keyword || undefined);
      setPipelinePage(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载流水线失败"));
      console.error(error);
    } finally {
      setPipelineLoading(false);
    }
  };

  const loadPipelineOptions = async () => {
    try {
      const data = await getIngestionPipelines(1, 200);
      setPipelineOptions(data.records || []);
    } catch (error) {
      console.error(error);
    }
  };

  const loadTasks = async (pageNo = taskPageNo, status = taskStatus) => {
    setTaskLoading(true);
    try {
      const data = await getIngestionTasks(pageNo, TASK_PAGE_SIZE, status);
      setTaskPage(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载任务失败"));
      console.error(error);
    } finally {
      setTaskLoading(false);
    }
  };

  useEffect(() => {
    loadPipelines();
  }, [pipelinePageNo, pipelineKeyword]);

  useEffect(() => {
    loadTasks();
  }, [taskPageNo, taskStatus]);

  useEffect(() => {
    loadPipelineOptions();
  }, []);

  useEffect(() => {
    if (tabParam === "tasks" || tabParam === "pipelines") {
      setActiveTab(tabParam);
      return;
    }
    setSearchParams({ tab: "pipelines" }, { replace: true });
  }, [tabParam, setSearchParams]);

  const handleTabChange = (next: "pipelines" | "tasks") => {
    setActiveTab(next);
    setSearchParams({ tab: next }, { replace: true });
  };

  const handlePipelineSearch = () => {
    setPipelinePageNo(1);
    setPipelineKeyword(pipelineSearch.trim());
  };

  const handlePipelineRefresh = () => {
    setPipelinePageNo(1);
    loadPipelines(1, pipelineKeyword);
    loadPipelineOptions();
  };

  const handleTaskRefresh = () => {
    setTaskPageNo(1);
    loadTasks(1, taskStatus);
    loadPipelineOptions();
  };

  const handlePipelineDelete = async () => {
    if (!pipelineDeleteTarget) return;
    try {
      await deleteIngestionPipeline(pipelineDeleteTarget.id);
      toast.success("删除成功");
      setPipelineDeleteTarget(null);
      await loadPipelines(1, pipelineKeyword);
      await loadPipelineOptions();
    } catch (error) {
      toast.error(getErrorMessage(error, "删除失败"));
      console.error(error);
    }
  };

  const openPipelineNodes = async (pipeline: IngestionPipeline) => {
    try {
      const detail = await getIngestionPipeline(pipeline.id);
      setPipelineNodesDialog({ open: true, pipeline: detail });
    } catch (error) {
      toast.error(getErrorMessage(error, "获取流水线详情失败"));
      console.error(error);
    }
  };

  const taskStatusLabel = (status?: string | null) =>
    status ? status.toLowerCase() : "unknown";

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">数据通道</h1>
          <p className="admin-page-subtitle">管理通道流水线与任务执行情况</p>
        </div>
        <div className="admin-page-actions">
          <Button
            variant={activeTab === "pipelines" ? "default" : "outline"}
            size="sm"
            onClick={() => handleTabChange("pipelines")}
          >
            <FolderKanban className="mr-2 h-4 w-4" />
            流水线
          </Button>
          <Button
            variant={activeTab === "tasks" ? "default" : "outline"}
            size="sm"
            onClick={() => handleTabChange("tasks")}
          >
            <ClipboardList className="mr-2 h-4 w-4" />
            任务
          </Button>
        </div>
      </div>

      {activeTab === "pipelines" ? (
        <Card>
          <CardHeader>
            <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <CardTitle>通道流水线</CardTitle>
                <CardDescription>配置节点顺序与处理逻辑</CardDescription>
              </div>
              <div className="flex flex-1 items-center justify-end gap-2">
                <Input
                  value={pipelineSearch}
                  onChange={(event) => setPipelineSearch(event.target.value)}
                  placeholder="搜索流水线名称"
                  className="w-[260px]"
                />
                <Button variant="outline" onClick={handlePipelineSearch}>
                  搜索
                </Button>
                <Button variant="outline" onClick={handlePipelineRefresh}>
                  <RefreshCw className="mr-2 h-4 w-4" />
                  刷新
                </Button>
                <Button
                  className="admin-primary-gradient"
                  onClick={() => setPipelineDialog({ open: true, mode: "create", pipeline: null })}
                >
                  <Plus className="mr-2 h-4 w-4" />
                  新建流水线
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {pipelineLoading ? (
              <div className="py-10 text-center text-muted-foreground">加载中...</div>
            ) : pipelines.length === 0 ? (
              <div className="py-10 text-center text-muted-foreground">暂无流水线</div>
            ) : (
              <Table className="min-w-[920px]">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[260px]">名称</TableHead>
                    <TableHead>描述</TableHead>
                    <TableHead className="w-[90px]">节点数</TableHead>
                    <TableHead className="w-[120px]">负责人</TableHead>
                    <TableHead className="w-[170px]">更新时间</TableHead>
                    <TableHead className="w-[180px] text-left">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {pipelines.map((pipeline) => (
                    <TableRow key={pipeline.id}>
                      <TableCell className="font-medium">{pipeline.name}</TableCell>
                      <TableCell className="text-muted-foreground">
                        {pipeline.description || "-"}
                      </TableCell>
                      <TableCell>{pipeline.nodes?.length ?? 0}</TableCell>
                      <TableCell>{pipeline.createdBy || "-"}</TableCell>
                      <TableCell>{formatDate(pipeline.updateTime)}</TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-2">
                          <Button size="sm" variant="outline" onClick={() => openPipelineNodes(pipeline)}>
                            查看节点
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setPipelineDialog({ open: true, mode: "edit", pipeline })}
                          >
                            <Pencil className="mr-0.1 h-4 w-4" />
                            编辑
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-destructive hover:text-destructive"
                            onClick={() => setPipelineDeleteTarget(pipeline)}
                          >
                            <Trash2 className="mr-0.1 h-4 w-4" />
                            删除
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}

            <Pagination
              current={pipelinePage?.current || 1}
              pages={pipelinePage?.pages || 1}
              total={pipelinePage?.total || 0}
              onPrev={() => setPipelinePageNo((prev) => Math.max(1, prev - 1))}
              onNext={() =>
                setPipelinePageNo((prev) => Math.min(pipelinePage?.pages || 1, prev + 1))
              }
            />
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardHeader>
            <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <CardTitle>通道任务</CardTitle>
                <CardDescription>监控执行状态与节点日志</CardDescription>
              </div>
              <div className="flex flex-1 flex-wrap items-center justify-end gap-2">
                <Select
                  value={taskStatus || "all"}
                  onValueChange={(value) => {
                    setTaskPageNo(1);
                    setTaskStatus(value === "all" ? undefined : value);
                  }}
                >
                  <SelectTrigger className="w-[180px]">
                    <SelectValue placeholder="任务状态" />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all">全部状态</SelectItem>
                    {STATUS_OPTIONS.map((option) => (
                      <SelectItem key={option.value} value={option.value}>
                        {option.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button variant="outline" onClick={handleTaskRefresh}>
                  <RefreshCw className="mr-2 h-4 w-4" />
                  刷新
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {taskLoading ? (
              <div className="py-10 text-center text-muted-foreground">加载中...</div>
            ) : tasks.length === 0 ? (
              <div className="py-10 text-center text-muted-foreground">暂无任务</div>
            ) : (
              <Table className="min-w-[980px]">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[220px]">任务ID</TableHead>
                    <TableHead>来源</TableHead>
                    <TableHead className="w-[120px]">状态</TableHead>
                    <TableHead className="w-[120px]">负责人</TableHead>
                    <TableHead className="w-[90px]">分片数</TableHead>
                    <TableHead className="w-[170px]">创建时间</TableHead>
                    <TableHead className="w-[140px] text-left">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {tasks.map((task) => (
                    <TableRow key={task.id}>
                      <TableCell className="font-mono text-xs">{task.id}</TableCell>
                      <TableCell>
                        <div className="text-sm">
                          <span className="font-medium">{task.sourceType || "-"}</span>
                          <span className="text-muted-foreground">
                            {" "}
                            {task.sourceFileName || task.sourceLocation || ""}
                          </span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={statusBadgeVariant(task.status)}>
                          {taskStatusLabel(task.status)}
                        </Badge>
                      </TableCell>
                      <TableCell>{task.createdBy || "-"}</TableCell>
                      <TableCell>{task.chunkCount ?? "-"}</TableCell>
                      <TableCell>{formatDate(task.createTime)}</TableCell>
                      <TableCell className="text-right">
                        <Button size="sm" variant="outline" onClick={() => setTaskDetail({ open: true, taskId: task.id })}>
                          查看详情
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}

            <Pagination
              current={taskPage?.current || 1}
              pages={taskPage?.pages || 1}
              total={taskPage?.total || 0}
              onPrev={() => setTaskPageNo((prev) => Math.max(1, prev - 1))}
              onNext={() => setTaskPageNo((prev) => Math.min(taskPage?.pages || 1, prev + 1))}
            />
          </CardContent>
        </Card>
      )}

      <PipelineDialog
        open={pipelineDialog.open}
        mode={pipelineDialog.mode}
        pipeline={pipelineDialog.pipeline}
        onOpenChange={(open) => setPipelineDialog((prev) => ({ ...prev, open }))}
        onSubmit={async (payload, mode) => {
          if (mode === "create") {
            await createIngestionPipeline(payload);
            toast.success("创建成功");
          } else if (pipelineDialog.pipeline) {
            await updateIngestionPipeline(pipelineDialog.pipeline.id, payload);
            toast.success("更新成功");
          }
          setPipelineDialog({ open: false, mode: "create", pipeline: null });
          await loadPipelines(1, pipelineKeyword);
          await loadPipelineOptions();
        }}
      />

      <PipelineNodesDialog
        open={pipelineNodesDialog.open}
        pipeline={pipelineNodesDialog.pipeline}
        onOpenChange={(open) => setPipelineNodesDialog({ open, pipeline: open ? pipelineNodesDialog.pipeline : null })}
      />

      <AlertDialog open={Boolean(pipelineDeleteTarget)} onOpenChange={(open) => (!open ? setPipelineDeleteTarget(null) : null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除流水线？</AlertDialogTitle>
            <AlertDialogDescription>
              流水线 [{pipelineDeleteTarget?.name}] 将被永久删除。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handlePipelineDelete} className="bg-destructive text-destructive-foreground">
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <TaskDetailDialog
        open={taskDetail.open}
        taskId={taskDetail.taskId}
        onOpenChange={(open) => setTaskDetail({ open, taskId: open ? taskDetail.taskId : null })}
      />
    </div>
  );
}

interface PaginationProps {
  current: number;
  pages: number;
  total: number;
  onPrev: () => void;
  onNext: () => void;
}

function Pagination({ current, pages, total, onPrev, onNext }: PaginationProps) {
  if (total === 0) return null;
  return (
    <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-gray-500">
      <span>共 {total} 条</span>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="sm" onClick={onPrev} disabled={current <= 1}>
          上一页
        </Button>
        <span>
          {current} / {pages}
        </span>
        <Button variant="outline" size="sm" onClick={onNext} disabled={current >= pages}>
          下一页
        </Button>
      </div>
    </div>
  );
}

interface PipelineDialogProps {
  open: boolean;
  mode: "create" | "edit";
  pipeline: IngestionPipeline | null;
  onOpenChange: (open: boolean) => void;
  onSubmit: (payload: IngestionPipelinePayload, mode: "create" | "edit") => Promise<void>;
}

function PipelineDialog({ open, mode, pipeline, onOpenChange, onSubmit }: PipelineDialogProps) {
  const [saving, setSaving] = useState(false);
  const [nodes, setNodes] = useState<PipelineNodeForm[]>([]);
  const [selectedTemplate, setSelectedTemplate] = useState<number | null>(null);
  const [conditionExpanded, setConditionExpanded] = useState<Record<string, boolean>>({});
  const [conditionAdvanced, setConditionAdvanced] = useState<Record<string, boolean>>({});
  const [expandedTasks, setExpandedTasks] = useState<Record<string, boolean>>({});

  const form = useForm<PipelineFormValues>({
    resolver: zodResolver(pipelineSchema),
    defaultValues: {
      name: pipeline?.name || "",
      description: pipeline?.description || ""
    }
  });

  /* ------------------------------------------------------------------ */
  /*  Helpers                                                            */
  /* ------------------------------------------------------------------ */

  const createLocalId = () => `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;

  const createTask = (type: string): EnhancerTaskForm => ({
    id: createLocalId(),
    type,
    enabled: true,
    systemPrompt: "",
    userPromptTemplate: ""
  });

  const createNode = (nodeType: PipelineNodeType = "fetcher"): PipelineNodeForm => ({
    id: createLocalId(),
    nodeId: "",
    nodeType,
    nextNodeId: "",
    condition: "",
    chunker: { strategy: "structure_aware", chunkSize: "", overlapSize: "", separator: "" },
    enhancer: { modelId: "", tasks: [] },
    enricher: { modelId: "", attachDocumentMetadata: true, tasks: [] },
    parser: { rulesJson: "" },
    indexer: { embeddingModel: "", metadataFields: "" }
  });

  const mapSettingsTasks = (tasks: unknown): EnhancerTaskForm[] => {
    if (!Array.isArray(tasks)) return [];
    return tasks.map((task) => ({
      id: createLocalId(),
      type: String((task as { type?: string }).type || ""),
      enabled: true,
      systemPrompt: String((task as { systemPrompt?: string }).systemPrompt || ""),
      userPromptTemplate: String((task as { userPromptTemplate?: string }).userPromptTemplate || "")
    }));
  };

  const buildNodeForm = (node: IngestionPipelineNode): PipelineNodeForm => {
    const settings = (node.settings as Record<string, unknown>) || {};
    const rawCondition = node.condition as unknown;
    const condition = rawCondition
      ? typeof rawCondition === "string"
        ? rawCondition
        : JSON.stringify(rawCondition, null, 2)
      : "";
    const tasks = mapSettingsTasks((settings as { tasks?: unknown }).tasks);
    const nodeType = (node.nodeType as PipelineNodeType) || "fetcher";
    return {
      id: createLocalId(),
      nodeId: node.nodeId || "",
      nodeType,
      nextNodeId: node.nextNodeId || "",
      condition,
      chunker: {
        strategy: String((settings as { strategy?: string }).strategy || "structure_aware"),
        chunkSize: settings.chunkSize != null ? String(settings.chunkSize) : "",
        overlapSize: settings.overlapSize != null ? String(settings.overlapSize) : "",
        separator: String((settings as { separator?: string }).separator || "")
      },
      enhancer: {
        modelId: String((settings as { modelId?: string }).modelId || ""),
        tasks: nodeType === "enhancer" ? tasks : []
      },
      enricher: {
        modelId: String((settings as { modelId?: string }).modelId || ""),
        attachDocumentMetadata:
          (settings as { attachDocumentMetadata?: boolean }).attachDocumentMetadata ?? true,
        tasks: nodeType === "enricher" ? tasks : []
      },
      parser: {
        rulesJson: Array.isArray((settings as { rules?: unknown }).rules)
          ? JSON.stringify((settings as { rules?: unknown }).rules, null, 2)
          : ""
      },
      indexer: {
        embeddingModel: String((settings as { embeddingModel?: string }).embeddingModel || ""),
        metadataFields: Array.isArray((settings as { metadataFields?: string[] }).metadataFields)
          ? (settings as { metadataFields?: string[] }).metadataFields?.join(", ") || ""
          : ""
      },
      _rawSettings: settings && Object.keys(settings).length > 0 ? { ...settings } : undefined
    };
  };

  const buildNodesFromPipeline = (source?: IngestionPipelineNode[] | null) => {
    if (!source || source.length === 0) return [];
    return source.map(buildNodeForm);
  };

  /* ------------------------------------------------------------------ */
  /*  Condition helpers                                                  */
  /* ------------------------------------------------------------------ */

  const parseConditionFields = (
    raw: string
  ): { field: string; op: string; value: string } | null => {
    if (!raw.trim()) return null;
    try {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === "object" && "field" in parsed && "op" in parsed) {
        return {
          field: String(parsed.field ?? ""),
          op: String(parsed.op ?? ""),
          value: String(parsed.value ?? "")
        };
      }
    } catch {
      /* not structured JSON – fall through */
    }
    return null;
  };

  const parseCondition = (raw: string): Record<string, unknown> | null => {
    const trimmed = raw.trim();
    if (!trimmed) return null;
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      const parsed: unknown = JSON.parse(trimmed);
      if (parsed && typeof parsed === "object") {
        return parsed as Record<string, unknown>;
      }
      // JSON 值但不是对象/数组（如 JSON 字符串），包装为 { value } 保证类型安全
      return { value: parsed };
    }
    // 纯文本条件包装为 { expr } 结构，确保返回值始终为对象
    return { expr: trimmed };
  };

  const updateConditionFromBuilder = (
    nodeId: string,
    field: string,
    op: string,
    value: string
  ) => {
    if (!field && !op && !value) {
      setNodes((prev) =>
        prev.map((n) => (n.id === nodeId ? { ...n, condition: "" } : n))
      );
    } else {
      const json = JSON.stringify({ field, op, value });
      setNodes((prev) =>
        prev.map((n) => (n.id === nodeId ? { ...n, condition: json } : n))
      );
    }
  };

  /* ------------------------------------------------------------------ */
  /*  Parser rules helpers                                               */
  /* ------------------------------------------------------------------ */

  const parseRulesMimes = (rulesJson: string): string[] => {
    if (!rulesJson.trim()) return [];
    try {
      const parsed = JSON.parse(rulesJson);
      const rules: unknown[] = Array.isArray(parsed)
        ? parsed
        : parsed && typeof parsed === "object" && Array.isArray((parsed as { rules?: unknown }).rules)
          ? (parsed as { rules: unknown[] }).rules
          : [];
      return rules
        .filter((r): r is { mimeType: string } => !!r && typeof (r as { mimeType?: string }).mimeType === "string")
        .map((r) => r.mimeType);
    } catch {
      return [];
    }
  };

  const updateRulesFromCheckboxes = (nodeId: string, mimes: string[]) => {
    const rules = mimes.map((mimeType) => ({ mimeType }));
    const rulesJson = rules.length > 0 ? JSON.stringify(rules, null, 2) : "";
    setNodes((prev) =>
      prev.map((n) =>
        n.id === nodeId ? { ...n, parser: { ...n.parser, rulesJson } } : n
      )
    );
  };

  /* ------------------------------------------------------------------ */
  /*  Enhancer / Enricher checkbox helpers                               */
  /* ------------------------------------------------------------------ */

  const toggleEnhancerTask = (nodeId: string, taskType: string, checked: boolean) => {
    setNodes((prev) =>
      prev.map((n) => {
        if (n.id !== nodeId) return n;
        const existing = n.enhancer.tasks.find((t) => t.type === taskType);
        if (existing) {
          // 软删除：保留 task 对象，仅切换 enabled 状态
          return {
            ...n,
            enhancer: {
              ...n.enhancer,
              tasks: n.enhancer.tasks.map((t) =>
                t.type === taskType ? { ...t, enabled: checked } : t
              )
            }
          };
        }
        // 不存在则新建
        if (checked) {
          return {
            ...n,
            enhancer: { ...n.enhancer, tasks: [...n.enhancer.tasks, createTask(taskType)] }
          };
        }
        return n;
      })
    );
  };

  const toggleEnricherTask = (nodeId: string, taskType: string, checked: boolean) => {
    setNodes((prev) =>
      prev.map((n) => {
        if (n.id !== nodeId) return n;
        const existing = n.enricher.tasks.find((t) => t.type === taskType);
        if (existing) {
          return {
            ...n,
            enricher: {
              ...n.enricher,
              tasks: n.enricher.tasks.map((t) =>
                t.type === taskType ? { ...t, enabled: checked } : t
              )
            }
          };
        }
        if (checked) {
          return {
            ...n,
            enricher: { ...n.enricher, tasks: [...n.enricher.tasks, createTask(taskType)] }
          };
        }
        return n;
      })
    );
  };

  const isTaskExpanded = (taskId: string) => expandedTasks[taskId] ?? false;

  const toggleTaskExpanded = (taskId: string) => {
    setExpandedTasks((prev) => ({ ...prev, [taskId]: !prev[taskId] }));
  };

  /* ------------------------------------------------------------------ */
  /*  Node reorder                                                       */
  /* ------------------------------------------------------------------ */

  const moveNode = (index: number, direction: "up" | "down") => {
    setNodes((prev) => {
      const next = [...prev];
      const target = direction === "up" ? index - 1 : index + 1;
      if (target < 0 || target >= next.length) return prev;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  };

  /* ------------------------------------------------------------------ */
  /*  Template selection                                                 */
  /* ------------------------------------------------------------------ */

  const applyTemplate = (templateIndex: number) => {
    setSelectedTemplate(templateIndex);
    const template = PIPELINE_TEMPLATES[templateIndex];
    setNodes(template.nodeTypes.map((nt) => createNode(nt)));
  };

  /* ------------------------------------------------------------------ */
  /*  Build settings (unchanged logic)                                   */
  /* ------------------------------------------------------------------ */

  /**
   * 将前端不处理的原始 settings 字段保留到输出中，避免编辑往返时丢失未知字段
   * （如旧流水线中的 collectionName 等）
   */
  const preserveUnknownFields = (
    formSettings: Record<string, unknown> | undefined,
    node: PipelineNodeForm
  ): Record<string, unknown> | undefined => {
    const raw = node._rawSettings;
    if (!raw || Object.keys(raw).length === 0) return formSettings;

    const knownKeys = new Set<string>([
      "strategy", "chunkSize", "overlapSize", "separator",
      "modelId", "tasks", "attachDocumentMetadata",
      "rules", "embeddingModel", "metadataFields"
    ]);
    const unknownEntries = Object.entries(raw).filter(([k]) => !knownKeys.has(k));
    if (unknownEntries.length === 0) return formSettings;

    const merged: Record<string, unknown> = {};
    for (const [k, v] of unknownEntries) merged[k] = v;
    if (formSettings) Object.assign(merged, formSettings);
    return merged;
  };

  const parseParserRules = (raw: string) => {
    const trimmed = raw.trim();
    if (!trimmed) return null;
    const parsed = JSON.parse(trimmed);
    if (Array.isArray(parsed)) {
      return { rules: parsed };
    }
    if (parsed && typeof parsed === "object" && Array.isArray((parsed as { rules?: unknown }).rules)) {
      return { rules: (parsed as { rules?: unknown }).rules };
    }
    throw new Error("解析规则必须是数组或包含 rules 字段的对象");
  };

  const buildSettings = (node: PipelineNodeForm) => {
    switch (node.nodeType) {
      case "chunker": {
        if (!node.chunker.strategy) {
          throw new Error("分块节点需要选择 strategy");
        }
        const chunkSize = node.chunker.chunkSize.trim();
        const overlapSize = node.chunker.overlapSize.trim();
        const chunkSizeValue = chunkSize ? Number(chunkSize) : undefined;
        const overlapSizeValue = overlapSize ? Number(overlapSize) : undefined;
        if (chunkSizeValue !== undefined && Number.isNaN(chunkSizeValue)) {
          throw new Error("chunkSize 必须是数字");
        }
        if (overlapSizeValue !== undefined && Number.isNaN(overlapSizeValue)) {
          throw new Error("overlapSize 必须是数字");
        }
        return {
          strategy: node.chunker.strategy,
          chunkSize: chunkSizeValue,
          overlapSize: overlapSizeValue,
          separator: node.chunker.separator.trim() || undefined
        };
      }
      case "enhancer": {
        const tasks = node.enhancer.tasks
          .filter((task) => task.type && task.enabled !== false)
          .map((task) => ({
            type: task.type,
            systemPrompt: task.systemPrompt.trim() || undefined,
            userPromptTemplate: task.userPromptTemplate.trim() || undefined
          }));
        const payload: Record<string, unknown> = {};
        if (node.enhancer.modelId.trim()) {
          payload.modelId = node.enhancer.modelId.trim();
        }
        if (tasks.length > 0) {
          payload.tasks = tasks;
        }
        return Object.keys(payload).length ? payload : undefined;
      }
      case "enricher": {
        const tasks = node.enricher.tasks
          .filter((task) => task.type && task.enabled !== false)
          .map((task) => ({
            type: task.type,
            systemPrompt: task.systemPrompt.trim() || undefined,
            userPromptTemplate: task.userPromptTemplate.trim() || undefined
          }));
        const payload: Record<string, unknown> = {
          attachDocumentMetadata: node.enricher.attachDocumentMetadata
        };
        if (node.enricher.modelId.trim()) {
          payload.modelId = node.enricher.modelId.trim();
        }
        if (tasks.length > 0) {
          payload.tasks = tasks;
        }
        return payload;
      }
      case "parser": {
        if (!node.parser.rulesJson.trim()) {
          return undefined;
        }
        return parseParserRules(node.parser.rulesJson);
      }
      case "indexer": {
        const fields = node.indexer.metadataFields
          .split(",")
          .map((item) => item.trim())
          .filter(Boolean);
        const payload: Record<string, unknown> = {};
        if (node.indexer.embeddingModel.trim()) {
          payload.embeddingModel = node.indexer.embeddingModel.trim();
        }
        if (fields.length > 0) {
          payload.metadataFields = fields;
        }
        return Object.keys(payload).length ? payload : undefined;
      }
      case "fetcher":
      default:
        return undefined;
    }
  };

  /* ------------------------------------------------------------------ */
  /*  Build nodes payload – auto-generate nodeId / nextNodeId            */
  /* ------------------------------------------------------------------ */

  const buildNodesPayload = (sourceNodes: PipelineNodeForm[]) => {
    const result: IngestionPipelinePayload["nodes"] = [];
    for (let i = 0; i < sourceNodes.length; i++) {
      const node = sourceNodes[i];
      const nodeId = `step_${i + 1}`;
      const nextNodeId = i < sourceNodes.length - 1 ? `step_${i + 2}` : null;

      if (!node.nodeType) {
        return { ok: false as const, message: "节点类型不能为空" };
      }

      let settings: Record<string, unknown> | undefined;
      let condition: unknown;
      try {
        const rawSettings = buildSettings(node) as Record<string, unknown> | undefined;
        settings = preserveUnknownFields(rawSettings, node);
        condition = parseCondition(node.condition);
      } catch (error) {
        return { ok: false as const, message: error instanceof Error ? error.message : "节点配置错误" };
      }

      result.push({
        nodeId,
        nodeType: node.nodeType,
        settings: settings ?? null,
        condition: condition ?? null,
        nextNodeId
      });
    }
    return { ok: true as const, nodes: result };
  };

  /* ------------------------------------------------------------------ */
  /*  Lifecycle                                                          */
  /* ------------------------------------------------------------------ */

  useEffect(() => {
    if (open) {
      form.reset({
        name: pipeline?.name || "",
        description: pipeline?.description || ""
      });
      setNodes(buildNodesFromPipeline(pipeline?.nodes));
      setSelectedTemplate(null);
      setConditionExpanded({});
      setConditionAdvanced({});
      setExpandedTasks({});
    }
  }, [open, pipeline, form]);

  /* ------------------------------------------------------------------ */
  /*  Submit                                                             */
  /* ------------------------------------------------------------------ */

  const handleSubmit = async (values: PipelineFormValues) => {
    if (nodes.length === 0) {
      toast.error("请至少添加一个节点");
      return;
    }
    const result = buildNodesPayload(nodes);
    if (!result.ok) {
      toast.error(result.message);
      return;
    }

    setSaving(true);
    try {
      const payload: IngestionPipelinePayload = {
        name: values.name.trim(),
        description: values.description?.trim() || undefined,
        nodes: result.nodes
      };
      await onSubmit(payload, mode);
    } catch (error) {
      toast.error(getErrorMessage(error, mode === "create" ? "创建失败" : "更新失败"));
      console.error(error);
    } finally {
      setSaving(false);
    }
  };

  /* ------------------------------------------------------------------ */
  /*  Update a single node field                                        */
  /* ------------------------------------------------------------------ */

  const updateNode = (nodeId: string, patch: Partial<PipelineNodeForm>) => {
    setNodes((prev) =>
      prev.map((n) => (n.id === nodeId ? { ...n, ...patch } : n))
    );
  };

  const updateNodeDeep = <K extends keyof PipelineNodeForm>(
    nodeId: string,
    key: K,
    patch: Partial<PipelineNodeForm[K]>
  ) => {
    setNodes((prev) =>
      prev.map((n) => {
        if (n.id !== nodeId) return n;
        const current = n[key];
        if (Array.isArray(current)) {
          // 数组类型直接替换，不做展开
          return { ...n, [key]: patch };
        }
        return { ...n, [key]: { ...(current as object), ...patch } };
      })
    );
  };

  const updateEnhancerTask = (
    nodeId: string,
    taskId: string,
    patch: Partial<EnhancerTaskForm>
  ) => {
    setNodes((prev) =>
      prev.map((n) =>
        n.id !== nodeId
          ? n
          : {
              ...n,
              enhancer: {
                ...n.enhancer,
                tasks: n.enhancer.tasks.map((t) =>
                  t.id === taskId ? { ...t, ...patch } : t
                )
              }
            }
      )
    );
  };

  const updateEnricherTask = (
    nodeId: string,
    taskId: string,
    patch: Partial<EnhancerTaskForm>
  ) => {
    setNodes((prev) =>
      prev.map((n) =>
        n.id !== nodeId
          ? n
          : {
              ...n,
              enricher: {
                ...n.enricher,
                tasks: n.enricher.tasks.map((t) =>
                  t.id === taskId ? { ...t, ...patch } : t
                )
              }
            }
      )
    );
  };

  /* ------------------------------------------------------------------ */
  /*  Render helpers                                                     */
  /* ------------------------------------------------------------------ */

  const renderConditionBuilder = (node: PipelineNodeForm) => {
    const cf = parseConditionFields(node.condition);
    const isAdvanced = conditionAdvanced[node.id] ?? false;

    if (isAdvanced) {
      return (
        <Textarea
          rows={2}
          value={node.condition}
          onChange={(e) => updateNode(node.id, { condition: e.target.value })}
          placeholder='{"field":"source_type","op":"eq","value":"file"} 或 #context.source.type == "file"'
        />
      );
    }

    return (
      <div className="grid gap-2 sm:grid-cols-3">
        <Select
          value={cf?.field || ""}
          onValueChange={(val) =>
            updateConditionFromBuilder(node.id, val, cf?.op || "eq", cf?.value || "")
          }
        >
          <SelectTrigger>
            <SelectValue placeholder="选择字段" />
          </SelectTrigger>
          <SelectContent>
            {CONDITION_FIELDS.map((f) => (
              <SelectItem key={f.value} value={f.value}>
                {f.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={cf?.op || ""}
          onValueChange={(val) =>
            updateConditionFromBuilder(node.id, cf?.field || "", val, cf?.value || "")
          }
        >
          <SelectTrigger>
            <SelectValue placeholder="运算符" />
          </SelectTrigger>
          <SelectContent>
            {CONDITION_OPERATORS.map((op) => (
              <SelectItem key={op.value} value={op.value}>
                {op.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Input
          value={cf?.value || ""}
          onChange={(e) =>
            updateConditionFromBuilder(node.id, cf?.field || "", cf?.op || "eq", e.target.value)
          }
          placeholder="值"
        />
      </div>
    );
  };

  /* ------------------------------------------------------------------ */
  /*  JSX                                                                */
  /* ------------------------------------------------------------------ */

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sidebar-scroll sm:max-w-[880px]">
        <DialogHeader>
          <DialogTitle>{mode === "create" ? "新建流水线" : "编辑流水线"}</DialogTitle>
          <DialogDescription>配置节点顺序与处理逻辑</DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form className="space-y-4" onSubmit={form.handleSubmit(handleSubmit)}>
            {/* ── Name ─────────────────────────────────────────────── */}
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>流水线名称</FormLabel>
                  <FormControl>
                    <Input placeholder="例如：通用文档通道" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* ── Description ──────────────────────────────────────── */}
            <FormField
              control={form.control}
              name="description"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>描述</FormLabel>
                  <FormControl>
                    <Textarea placeholder="说明流水线用途或流程" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            {/* ── Template selector (create mode only) ─────────────── */}
            {mode === "create" && (
              <div className="space-y-2">
                <label className="text-sm font-medium">选择模板</label>
                <div className="grid gap-3 sm:grid-cols-3">
                  {PIPELINE_TEMPLATES.map((template, idx) => (
                    <button
                      key={idx}
                      type="button"
                      className={`rounded-lg border p-3 text-left transition-colors hover:bg-accent ${
                        selectedTemplate === idx
                          ? "border-primary bg-primary/5 ring-1 ring-primary"
                          : ""
                      }`}
                      onClick={() => applyTemplate(idx)}
                    >
                      <div className="text-sm font-medium">{template.name}</div>
                      <div className="mt-1 text-xs text-muted-foreground">
                        {template.description}
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* ── Node config header ───────────────────────────────── */}
            <div className="text-sm font-medium">节点配置</div>

            {/* ── Flow overview bar ────────────────────────────────── */}
            {nodes.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5 rounded-lg bg-muted/50 px-3 py-2">
                {nodes.map((node, idx) => (
                  <Fragment key={node.id}>
                    {idx > 0 && (
                      <span className="text-sm text-muted-foreground select-none">&rarr;</span>
                    )}
                    <Badge variant="outline" className="text-xs whitespace-nowrap">
                      {getNodeTypeShortLabel(node.nodeType)}
                    </Badge>
                  </Fragment>
                ))}
              </div>
            )}

            {/* ── Node list ────────────────────────────────────────── */}
            <div className="space-y-4">
              {nodes.length === 0 && (
                <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
                  {mode === "create"
                    ? "请先选择一个模板来初始化节点"
                    : "暂无节点，请添加节点配置"}
                </div>
              )}

              {nodes.map((node, index) => (
                <div key={node.id} className="rounded-lg border p-4 space-y-4">
                  {/* ── Node header with reorder / delete ────────── */}
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <Badge variant="outline">{getNodeTypeLabel(node.nodeType)}</Badge>
                      <span className="text-sm text-muted-foreground">节点 {index + 1}</span>
                    </div>
                    <div className="flex items-center gap-1">
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        disabled={index === 0}
                        onClick={() => moveNode(index, "up")}
                        aria-label="上移"
                      >
                        <ChevronUp className="h-4 w-4" />
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        disabled={index === nodes.length - 1}
                        onClick={() => moveNode(index, "down")}
                        aria-label="下移"
                      >
                        <ChevronDown className="h-4 w-4" />
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="ghost"
                        className="text-destructive hover:text-destructive"
                        onClick={() =>
                          setNodes((prev) => prev.filter((item) => item.id !== node.id))
                        }
                      >
                        删除
                      </Button>
                    </div>
                  </div>

                  {/* ── Node type selector ───────────────────────── */}
                  <div className="space-y-2">
                    <label className="text-sm font-medium">节点类型</label>
                    <Select
                      value={node.nodeType}
                      onValueChange={(value) =>
                        updateNode(node.id, { nodeType: value as PipelineNodeType })
                      }
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="选择节点类型" />
                      </SelectTrigger>
                      <SelectContent>
                        {NODE_TYPE_OPTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  {/* ── Fetcher ──────────────────────────────────── */}
                  {node.nodeType === "fetcher" && (
                    <div className="rounded-lg bg-muted/50 p-3 text-sm text-muted-foreground">
                      Fetcher 无额外配置
                    </div>
                  )}

                  {/* ── Parser – checkbox MIME rules ─────────────── */}
                  {node.nodeType === "parser" && (() => {
                    const selectedMimes = parseRulesMimes(node.parser.rulesJson);
                    return (
                      <div className="space-y-3">
                        <label className="text-sm font-medium">支持的文档类型</label>
                        <div className="grid gap-2 sm:grid-cols-3">
                          {PARSER_MIME_OPTIONS.map((mime) => {
                            const isChecked = selectedMimes.includes(mime.value);
                            return (
                              <label
                                key={mime.value}
                                className="flex items-center gap-2 rounded-md border p-2 cursor-pointer hover:bg-accent/50"
                              >
                                <Checkbox
                                  checked={isChecked}
                                  onCheckedChange={(checked) => {
                                    const next = checked
                                      ? [...selectedMimes, mime.value]
                                      : selectedMimes.filter((m) => m !== mime.value);
                                    updateRulesFromCheckboxes(node.id, next);
                                  }}
                                />
                                <span className="text-sm">{mime.label}</span>
                              </label>
                            );
                          })}
                        </div>
                      </div>
                    );
                  })()}

                  {/* ── Chunker ──────────────────────────────────── */}
                  {node.nodeType === "chunker" && (
                    <div className="grid gap-4 md:grid-cols-2">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">分块策略</label>
                        <Select
                          value={node.chunker.strategy}
                          onValueChange={(value) =>
                            updateNodeDeep(node.id, "chunker", { strategy: value })
                          }
                        >
                          <SelectTrigger>
                            <SelectValue placeholder="选择策略" />
                          </SelectTrigger>
                          <SelectContent>
                            {CHUNK_STRATEGY_OPTIONS.map((option) => (
                              <SelectItem key={option.value} value={option.value}>
                                {option.label}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Chunk Size</label>
                        <Input
                          type="number"
                          value={node.chunker.chunkSize}
                          onChange={(e) =>
                            updateNodeDeep(node.id, "chunker", { chunkSize: e.target.value })
                          }
                          placeholder="例如：512"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Overlap Size</label>
                        <Input
                          type="number"
                          value={node.chunker.overlapSize}
                          onChange={(e) =>
                            updateNodeDeep(node.id, "chunker", { overlapSize: e.target.value })
                          }
                          placeholder="例如：128"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">自定义分隔符</label>
                        <Input
                          value={node.chunker.separator}
                          onChange={(e) =>
                            updateNodeDeep(node.id, "chunker", { separator: e.target.value })
                          }
                          placeholder="可选"
                        />
                      </div>
                    </div>
                  )}

                  {/* ── Enhancer – checkbox tasks ──────────────── */}
                  {node.nodeType === "enhancer" && (
                    <div className="space-y-4">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">模型ID</label>
                        <Input
                          value={node.enhancer.modelId}
                          onChange={(e) =>
                            updateNodeDeep(node.id, "enhancer", { modelId: e.target.value })
                          }
                          placeholder="可选"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">增强任务</label>
                        <div className="grid gap-2 sm:grid-cols-2">
                          {ENHANCER_TASK_OPTIONS.map((taskOpt) => {
                            const task = node.enhancer.tasks.find(
                              (t) => t.type === taskOpt.value
                            );
                            const isChecked = !!task && task.enabled !== false;
                            return (
                              <div key={taskOpt.value} className="space-y-2">
                                <label className="flex items-center gap-2 rounded-md border p-2 cursor-pointer hover:bg-accent/50">
                                  <Checkbox
                                    checked={isChecked}
                                    onCheckedChange={(checked) =>
                                      toggleEnhancerTask(node.id, taskOpt.value, !!checked)
                                    }
                                  />
                                  <span className="text-sm flex-1">{taskOpt.label}</span>
                                  {isChecked && (
                                    <Button
                                      type="button"
                                      size="sm"
                                      variant="ghost"
                                      className="ml-auto h-6 w-6 p-0"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        toggleTaskExpanded(task.id);
                                      }}
                                    >
                                      {isTaskExpanded(task.id) ? (
                                        <ChevronUp className="h-3 w-3" />
                                      ) : (
                                        <ChevronDown className="h-3 w-3" />
                                      )}
                                    </Button>
                                  )}
                                </label>
                                {isChecked && isTaskExpanded(task.id) && (
                                  <div className="ml-6 space-y-2">
                                    <div className="space-y-1">
                                      <label className="text-xs text-muted-foreground">
                                        System Prompt
                                      </label>
                                      <Textarea
                                        rows={2}
                                        value={task.systemPrompt}
                                        onChange={(e) =>
                                          updateEnhancerTask(node.id, task.id, {
                                            systemPrompt: e.target.value
                                          })
                                        }
                                        placeholder="可选"
                                      />
                                    </div>
                                    <div className="space-y-1">
                                      <label className="text-xs text-muted-foreground">
                                        User Prompt 模板
                                      </label>
                                      <Textarea
                                        rows={2}
                                        value={task.userPromptTemplate}
                                        onChange={(e) =>
                                          updateEnhancerTask(node.id, task.id, {
                                            userPromptTemplate: e.target.value
                                          })
                                        }
                                        placeholder="可选"
                                      />
                                    </div>
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* ── Enricher – checkbox tasks ──────────────── */}
                  {node.nodeType === "enricher" && (
                    <div className="space-y-4">
                      <div className="grid gap-4 md:grid-cols-2">
                        <div className="space-y-2">
                          <label className="text-sm font-medium">模型ID</label>
                          <Input
                            value={node.enricher.modelId}
                            onChange={(e) =>
                              updateNodeDeep(node.id, "enricher", { modelId: e.target.value })
                            }
                            placeholder="可选"
                          />
                        </div>
                        <div className="space-y-2">
                          <label className="text-sm font-medium">附加文档元数据</label>
                          <Select
                            value={node.enricher.attachDocumentMetadata ? "true" : "false"}
                            onValueChange={(value) =>
                              updateNodeDeep(node.id, "enricher", {
                                attachDocumentMetadata: value === "true"
                              })
                            }
                          >
                            <SelectTrigger>
                              <SelectValue placeholder="选择" />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="true">是</SelectItem>
                              <SelectItem value="false">否</SelectItem>
                            </SelectContent>
                          </Select>
                        </div>
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">富集任务</label>
                        <div className="grid gap-2 sm:grid-cols-2">
                          {ENRICHER_TASK_OPTIONS.map((taskOpt) => {
                            const task = node.enricher.tasks.find(
                              (t) => t.type === taskOpt.value
                            );
                            const isChecked = !!task && task.enabled !== false;
                            return (
                              <div key={taskOpt.value} className="space-y-2">
                                <label className="flex items-center gap-2 rounded-md border p-2 cursor-pointer hover:bg-accent/50">
                                  <Checkbox
                                    checked={isChecked}
                                    onCheckedChange={(checked) =>
                                      toggleEnricherTask(node.id, taskOpt.value, !!checked)
                                    }
                                  />
                                  <span className="text-sm flex-1">{taskOpt.label}</span>
                                  {isChecked && (
                                    <Button
                                      type="button"
                                      size="sm"
                                      variant="ghost"
                                      className="ml-auto h-6 w-6 p-0"
                                      onClick={(e) => {
                                        e.stopPropagation();
                                        toggleTaskExpanded(task.id);
                                      }}
                                    >
                                      {isTaskExpanded(task.id) ? (
                                        <ChevronUp className="h-3 w-3" />
                                      ) : (
                                        <ChevronDown className="h-3 w-3" />
                                      )}
                                    </Button>
                                  )}
                                </label>
                                {isChecked && isTaskExpanded(task.id) && (
                                  <div className="ml-6 space-y-2">
                                    <div className="space-y-1">
                                      <label className="text-xs text-muted-foreground">
                                        System Prompt
                                      </label>
                                      <Textarea
                                        rows={2}
                                        value={task.systemPrompt}
                                        onChange={(e) =>
                                          updateEnricherTask(node.id, task.id, {
                                            systemPrompt: e.target.value
                                          })
                                        }
                                        placeholder="可选"
                                      />
                                    </div>
                                    <div className="space-y-1">
                                      <label className="text-xs text-muted-foreground">
                                        User Prompt 模板
                                      </label>
                                      <Textarea
                                        rows={2}
                                        value={task.userPromptTemplate}
                                        onChange={(e) =>
                                          updateEnricherTask(node.id, task.id, {
                                            userPromptTemplate: e.target.value
                                          })
                                        }
                                        placeholder="可选"
                                      />
                                    </div>
                                  </div>
                                )}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* ── Indexer ─────────────────────────────────── */}
                  {node.nodeType === "indexer" && (
                    <div className="grid gap-4 md:grid-cols-2">
                      <div className="space-y-2">
                        <label className="text-sm font-medium">Embedding 模型</label>
                        <Input
                          value={node.indexer.embeddingModel}
                          onChange={(e) =>
                            updateNodeDeep(node.id, "indexer", {
                              embeddingModel: e.target.value
                            })
                          }
                          placeholder="可选，使用系统默认模型"
                        />
                      </div>
                      <div className="space-y-2">
                        <label className="text-sm font-medium">元数据字段</label>
                        <Input
                          value={node.indexer.metadataFields}
                          onChange={(e) =>
                            updateNodeDeep(node.id, "indexer", {
                              metadataFields: e.target.value
                            })
                          }
                          placeholder="用逗号分隔，如 keywords,summary"
                        />
                        <p className="text-xs text-muted-foreground">
                          向量将自动写入触发流水线知识库对应的向量集合
                        </p>
                      </div>
                    </div>
                  )}

                  {/* ── Condition builder ───────────────────────── */}
                  <div className="space-y-2">
                    <div className="flex items-center gap-3">
                      <button
                        type="button"
                        className="flex items-center gap-1 text-sm font-medium"
                        onClick={() =>
                          setConditionExpanded((prev) => ({
                            ...prev,
                            [node.id]: !prev[node.id]
                          }))
                        }
                      >
                        {conditionExpanded[node.id] ? (
                          <ChevronUp className="h-3 w-3" />
                        ) : (
                          <ChevronDown className="h-3 w-3" />
                        )}
                        条件（可选）
                      </button>
                      {conditionExpanded[node.id] && (
                        <label className="flex items-center gap-1.5 text-xs text-muted-foreground cursor-pointer ml-auto">
                          <Checkbox
                            checked={conditionAdvanced[node.id] ?? false}
                            onCheckedChange={(checked) =>
                              setConditionAdvanced((prev) => ({
                                ...prev,
                                [node.id]: !!checked
                              }))
                            }
                          />
                          高级
                        </label>
                      )}
                    </div>
                    {conditionExpanded[node.id] && renderConditionBuilder(node)}
                  </div>
                </div>
              ))}

              {/* ── Add node ──────────────────────────────────────── */}
              <Button
                type="button"
                variant="outline"
                onClick={() => setNodes((prev) => [...prev, createNode()])}
              >
                <Plus className="mr-2 h-4 w-4" />
                添加节点
              </Button>
            </div>

            {/* ── Footer ──────────────────────────────────────────── */}
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => onOpenChange(false)}
                disabled={saving}
              >
                取消
              </Button>
              <Button type="submit" disabled={saving}>
                {saving ? "保存中..." : "保存"}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
}

interface PipelineNodesDialogProps {
  open: boolean;
  pipeline: IngestionPipeline | null;
  onOpenChange: (open: boolean) => void;
}

function PipelineNodesDialog({ open, pipeline, onOpenChange }: PipelineNodesDialogProps) {
  const nodes = pipeline?.nodes || [];
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="max-h-[90vh] overflow-y-auto sidebar-scroll sm:max-w-[720px]"
        onOpenAutoFocus={(event) => event.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>流水线节点</DialogTitle>
          <DialogDescription>{pipeline?.name || ""}</DialogDescription>
        </DialogHeader>
        {nodes.length === 0 ? (
          <div className="py-6 text-center text-muted-foreground">暂无节点</div>
        ) : (
          <Table className="min-w-[640px]">
            <TableHeader>
              <TableRow>
                <TableHead className="w-[60px]">#</TableHead>
                <TableHead className="w-[160px]">节点ID</TableHead>
                <TableHead className="w-[120px]">类型</TableHead>
                <TableHead className="w-[140px]">下一节点</TableHead>
                <TableHead>配置</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {nodes.map((node, index) => (
                <TableRow key={node.id || `${node.nodeId}-${index}`}>
                  <TableCell>{index + 1}</TableCell>
                  <TableCell className="font-mono text-xs">{node.nodeId}</TableCell>
                  <TableCell>
                    <Badge variant="outline">{getNodeTypeLabel(node.nodeType)}</Badge>
                  </TableCell>
                  <TableCell>{node.nextNodeId || "-"}</TableCell>
                  <TableCell>
                    <pre className="max-w-[280px] whitespace-pre-wrap text-xs text-muted-foreground">
                      {truncateJson(node.settings || node.condition)}
                    </pre>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DialogContent>
    </Dialog>
  );
}

interface TaskDetailDialogProps {
  open: boolean;
  taskId: string | null;
  onOpenChange: (open: boolean) => void;
}

function TaskDetailDialog({ open, taskId, onOpenChange }: TaskDetailDialogProps) {
  const [task, setTask] = useState<IngestionTask | null>(null);
  const [nodes, setNodes] = useState<IngestionTaskNode[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open || !taskId) return;
    let active = true;
    const load = async () => {
      setLoading(true);
      try {
        const [detail, nodeLogs] = await Promise.all([
          getIngestionTask(taskId),
          getIngestionTaskNodes(taskId)
        ]);
        if (!active) return;
        setTask(detail);
        setNodes(nodeLogs || []);
      } catch (error) {
        toast.error(getErrorMessage(error, "加载任务详情失败"));
        console.error(error);
      } finally {
        if (active) setLoading(false);
      }
    };
    load();
    return () => {
      active = false;
    };
  }, [open, taskId]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sidebar-scroll sm:max-w-[820px]">
        <DialogHeader>
          <DialogTitle>任务详情</DialogTitle>
          <DialogDescription>{taskId || ""}</DialogDescription>
        </DialogHeader>
        {loading || !task ? (
          <div className="py-6 text-center text-muted-foreground">加载中...</div>
        ) : (
          <div className="space-y-6">
            <div className="grid gap-4 md:grid-cols-2">
              <div className="space-y-2">
                <div className="flex items-center gap-2">
                  <Badge variant={statusBadgeVariant(task.status)}>{task.status || "-"}</Badge>
                  {task.errorMessage ? (
                    <Badge variant="destructive">error</Badge>
                  ) : null}
                </div>
                <div className="text-sm text-muted-foreground">Pipeline: {task.pipelineId}</div>
                <div className="text-sm text-muted-foreground">
                  Source: {task.sourceType || "-"} {task.sourceFileName || task.sourceLocation || ""}
                </div>
                <div className="text-sm text-muted-foreground">Chunks: {task.chunkCount ?? "-"}</div>
              </div>
              <div className="space-y-2 text-sm text-muted-foreground">
                <div>Created: {formatDate(task.createTime)}</div>
                <div>Started: {formatDate(task.startedAt)}</div>
                <div>Completed: {formatDate(task.completedAt)}</div>
              </div>
            </div>

            {task.errorMessage ? (
              <div className="rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
                {task.errorMessage}
              </div>
            ) : null}

            <div>
              <h3 className="text-sm font-medium">任务元数据</h3>
              <pre className="mt-2 rounded-lg bg-muted p-3 text-xs text-muted-foreground">
                {stringifyJson(task.metadata)}
              </pre>
            </div>

            <div>
              <h3 className="text-sm font-medium">节点执行日志</h3>
              {nodes.length === 0 ? (
                <div className="mt-2 text-sm text-muted-foreground">暂无节点日志</div>
              ) : (
                <Table className="min-w-[720px]">
                  <TableHeader>
                    <TableRow>
                      <TableHead className="w-[180px]">节点</TableHead>
                      <TableHead className="w-[120px]">类型</TableHead>
                      <TableHead className="w-[100px]">状态</TableHead>
                      <TableHead className="w-[110px]">耗时</TableHead>
                      <TableHead>消息</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {nodes.map((node) => (
                      <TableRow key={node.id}>
                        <TableCell className="font-mono text-xs">{node.nodeId}</TableCell>
                        <TableCell>{node.nodeType}</TableCell>
                        <TableCell>
                          <Badge variant={nodeStatusVariant(node.status)}>{node.status || "-"}</Badge>
                        </TableCell>
                        <TableCell>{node.durationMs ?? "-"} ms</TableCell>
                        <TableCell>
                          <div className="text-xs text-muted-foreground">
                            {node.message || node.errorMessage || "-"}
                          </div>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
