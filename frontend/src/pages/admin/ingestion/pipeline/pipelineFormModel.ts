import type { IngestionPipelineNode } from "@/services/ingestionService";

/* ==========================================================================
   类型与常量（与 IngestionPage 表单逻辑保持一致，供画布属性面板复用）
   ========================================================================== */

export type PipelineNodeType =
  | "fetcher"
  | "parser"
  | "enhancer"
  | "chunker"
  | "enricher"
  | "indexer"
  | "graph_extractor";

export const NODE_TYPE_OPTIONS: { value: PipelineNodeType; label: string; short: string; description: string }[] = [
  { value: "fetcher", label: "获取文档", short: "获取文档", description: "从数据源获取文档原始字节流" },
  { value: "parser", label: "解析文本", short: "解析文本", description: "将原始字节解析为结构化文本" },
  { value: "enhancer", label: "文档增强", short: "文档增强", description: "整个文档级别的 AI 增强处理" },
  { value: "chunker", label: "文本分块", short: "文本分块", description: "将文本按策略切分为 Chunk" },
  { value: "enricher", label: "Chunk 富化", short: "Chunk 富化", description: "对每个 Chunk 进行 AI 元数据富化" },
  { value: "indexer", label: "向量入库", short: "向量入库", description: "将 Chunk 向量化并写入向量库" },
  { value: "graph_extractor", label: "图谱抽取", short: "图谱抽取", description: "LLM 抽取实体与关系写入知识图谱（需在向量入库之后）" }
];

export const getNodeTypeLabel = (type: string): string =>
  NODE_TYPE_OPTIONS.find((o) => o.value === type)?.label ?? type;

export const getNodeTypeShortLabel = (type: string): string =>
  NODE_TYPE_OPTIONS.find((o) => o.value === type)?.short ?? type;

export const CHUNK_STRATEGY_OPTIONS = [
  { value: "fixed_size", label: "固定大小" },
  { value: "recursive", label: "递归切分" },
  { value: "structure_aware", label: "语义感知（推荐）" }
];

export const ENHANCER_TASK_OPTIONS = [
  { value: "context_enhance", label: "上下文增强" },
  { value: "keywords", label: "关键词提取" },
  { value: "questions", label: "问题生成" },
  { value: "metadata", label: "元数据生成" }
];

export const ENRICHER_TASK_OPTIONS = [
  { value: "keywords", label: "关键词提取" },
  { value: "summary", label: "摘要生成" },
  { value: "metadata", label: "元数据富化" }
];

export const PARSER_MIME_OPTIONS = [
  { value: "application/pdf", label: "PDF" },
  { value: "text/markdown", label: "Markdown" },
  { value: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", label: "Word" },
  { value: "text/plain", label: "Plain Text" },
  { value: "text/html", label: "HTML" }
];

/** 条件构建器字段（值使用后端可解析的路径，避免旧版 source_type 映射问题） */
export const CONDITION_FIELDS = [
  { value: "source.type", label: "来源类型" },
  { value: "source.fileName", label: "文件名" },
  { value: "mimeType", label: "MIME 类型" }
];

export const CONDITION_OPERATORS = [
  { value: "eq", label: "等于" },
  { value: "ne", label: "不等于" },
  { value: "contains", label: "包含" },
  { value: "regex", label: "正则匹配" }
];

export interface PipelineTemplate {
  name: string;
  description: string;
  nodeTypes: PipelineNodeType[];
}

export const PIPELINE_TEMPLATES: PipelineTemplate[] = [
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
  },
  {
    name: "空白画布",
    description: "从开始/结束节点起步，完全自定义流程",
    nodeTypes: []
  }
];

export interface EnhancerTaskForm {
  id: string;
  type: string;
  enabled: boolean;
  systemPrompt: string;
  userPromptTemplate: string;
}

export interface PipelineNodeForm {
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

/* ==========================================================================
   构造与转换
   ========================================================================== */

export const createLocalId = () => `${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;

export const createTask = (type: string): EnhancerTaskForm => ({
  id: createLocalId(),
  type,
  enabled: true,
  systemPrompt: "",
  userPromptTemplate: ""
});

export const createNodeForm = (nodeType: PipelineNodeType = "fetcher"): PipelineNodeForm => ({
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

export const mapSettingsTasks = (tasks: unknown): EnhancerTaskForm[] => {
  if (!Array.isArray(tasks)) return [];
  return tasks.map((task) => ({
    id: createLocalId(),
    type: String((task as { type?: string }).type || ""),
    enabled: true,
    systemPrompt: String((task as { systemPrompt?: string }).systemPrompt || ""),
    userPromptTemplate: String((task as { userPromptTemplate?: string }).userPromptTemplate || "")
  }));
};

/** 后端节点（线性接口或画布图节点）→ 表单模型 */
export const buildNodeForm = (node: {
  nodeId?: string;
  nodeType?: string;
  nextNodeId?: string | null;
  condition?: unknown;
  settings?: unknown;
}): PipelineNodeForm => {
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
      embeddingModel: (settings as { embeddingModel?: string }).embeddingModel
        ? String((settings as { embeddingModel?: string }).embeddingModel)
        : "__default__",
      metadataFields: Array.isArray((settings as { metadataFields?: string[] }).metadataFields)
        ? (settings as { metadataFields?: string[] }).metadataFields?.join(", ") || ""
        : ""
    },
    _rawSettings: settings && Object.keys(settings).length > 0 ? { ...settings } : undefined
  };
};

export const buildNodesFromPipeline = (source?: IngestionPipelineNode[] | null): PipelineNodeForm[] => {
  if (!source || source.length === 0) return [];
  return source.map((node) => buildNodeForm(node));
};

/* ==========================================================================
   条件 / 解析规则 / 设置构建
   ========================================================================== */

export const parseConditionFields = (
  raw: string
): { field: string; op: string; value: string } | null => {
  if (!raw.trim()) return null;
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object" && "field" in parsed) {
      return {
        field: String(parsed.field ?? ""),
        op: String(parsed.operator ?? parsed.op ?? "eq"),
        value: String(parsed.value ?? "")
      };
    }
  } catch {
    /* not structured JSON – fall through */
  }
  return null;
};

export const parseCondition = (raw: string): Record<string, unknown> | null => {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    const parsed: unknown = JSON.parse(trimmed);
    if (parsed && typeof parsed === "object") {
      return parsed as Record<string, unknown>;
    }
    return { value: parsed };
  }
  // 纯文本条件包装为 { expr } 结构（后端 ConditionEvaluator 已支持）
  return { expr: trimmed };
};

export const buildConditionJson = (field: string, op: string, value: string): string =>
  JSON.stringify({ field, operator: op, value });

export const parseRulesMimes = (rulesJson: string): string[] => {
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

export const rulesJsonFromMimes = (mimes: string[]): string =>
  mimes.length > 0 ? JSON.stringify(mimes.map((mimeType) => ({ mimeType })), null, 2) : "";

export const parseParserRules = (raw: string): Record<string, unknown> | null => {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const parsed: unknown = JSON.parse(trimmed);
  if (Array.isArray(parsed)) {
    return { rules: parsed };
  }
  if (parsed && typeof parsed === "object") {
    return parsed as Record<string, unknown>;
  }
  return null;
};

/** 将前端不处理的原始 settings 字段保留到输出中，避免编辑往返时丢失未知字段 */
export const preserveUnknownFields = (
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

/** 表单 → settings JSON 对象（按节点类型分支） */
export const buildSettings = (node: PipelineNodeForm): Record<string, unknown> | undefined => {
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
      return parseParserRules(node.parser.rulesJson) ?? undefined;
    }
    case "indexer": {
      const fields = node.indexer.metadataFields
        .split(",")
        .map((item) => item.trim())
        .filter(Boolean);
      const payload: Record<string, unknown> = {};
      const embedModel = node.indexer.embeddingModel.trim();
      if (embedModel && embedModel !== "__default__") {
        payload.embeddingModel = embedModel;
      }
      if (fields.length > 0) {
        payload.metadataFields = fields;
      }
      return Object.keys(payload).length ? payload : undefined;
    }
    case "fetcher":
    case "graph_extractor":
    default:
      return undefined;
  }
};

/** 表单 → 画布图节点 data（settings + condition） */
export const buildGraphNodeData = (node: PipelineNodeForm): {
  settings: Record<string, unknown> | null;
  condition: Record<string, unknown> | null;
} => {
  const settings = preserveUnknownFields(buildSettings(node), node);
  const condition = parseCondition(node.condition);
  return {
    settings: settings ?? null,
    condition: condition ?? null
  };
};

/** 表单 → 节点设置摘要（画布节点副标题用） */
export const summarizeNode = (nodeType: PipelineNodeType, node: PipelineNodeForm): string => {
  switch (nodeType) {
    case "chunker": {
      const parts = [node.chunker.strategy];
      if (node.chunker.chunkSize.trim()) parts.push(`size=${node.chunker.chunkSize.trim()}`);
      return parts.join(" · ");
    }
    case "indexer": {
      const model = node.indexer.embeddingModel.trim();
      return model && model !== "__default__" ? model : "系统默认向量模型";
    }
    case "parser": {
      const mimes = parseRulesMimes(node.parser.rulesJson);
      return mimes.length > 0 ? `${mimes.length} 种类型` : "全部类型";
    }
    case "enhancer":
    case "enricher": {
      const section = nodeType === "enhancer" ? node.enhancer : node.enricher;
      const enabled = section.tasks.filter((t) => t.enabled !== false).length;
      return enabled > 0 ? `${enabled} 项任务` : "未配置任务";
    }
    default:
      return "";
  }
};
