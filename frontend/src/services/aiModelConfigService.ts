import { api } from "@/services/api";

// ==================== 类型定义 ====================

export interface AiProvider {
  id: string;
  name: string;
  displayName?: string | null;
  baseUrl: string;
  apiKey?: string | null;
  hasApiKey?: boolean | null;
  endpoints?: Record<string, string> | null;
  enabled: number;
  iconUrl?: string | null;
  modelCount?: number;
  apiProtocol?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface AiModel {
  id: string;
  providerId: string;
  providerName?: string | null;
  modelId: string;
  modelName: string;
  capability: string;
  isDefault: number;
  priority: number;
  enabled: number;
  supportsThinking: number;
  supportsMultimodal?: number;
  dimension?: number[] | null;
  customUrl?: string | null;
  apiProtocol?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface AiProviderPayload {
  name?: string;
  displayName?: string;
  baseUrl?: string;
  apiKey?: string;
  endpoints?: Record<string, string>;
  enabled?: number;
  apiProtocol?: string;
}

export interface AiModelPayload {
  providerId?: string;
  modelId?: string;
  modelName?: string;
  capability?: string;
  isDefault?: number;
  priority?: number;
  enabled?: number;
  supportsThinking?: number;
  supportsMultimodal?: number;
  dimension?: number[];
  customUrl?: string;
  apiProtocol?: string;
}

export interface ModelPriorityItem {
  id: string;
  priority: number;
}

// ==================== 调用策略 ====================

export interface CallingStrategyInfo {
  key: "sdk" | "openai" | "anthropic";
  label: string;
  className: string;
  dotColor: string;
}

/**
 * 根据供应商名 + 生效协议推导「调用策略」（与后端 ProviderGatewayRegistry 一致）：
 * - dashscope 协议 → 官方 SDK（DashScope）
 * - anthropic 协议 → Anthropic 兼容（anthropic-java）
 * - openai 协议：智谱 / 火山由官方 SDK 承载（zai-sdk / ark-runtime），其余为 OpenAI 兼容（openai-java）
 */
export function resolveCallingStrategy(
  providerName: string | null | undefined,
  protocol?: string | null
): CallingStrategyInfo {
  const p = (protocol || "openai").toLowerCase();
  const n = (providerName || "").toLowerCase();
  if (p === "dashscope") {
    return {
      key: "sdk",
      label: "官方 SDK",
      className: "border-indigo-200 bg-indigo-50 text-indigo-600",
      dotColor: "bg-indigo-500"
    };
  }
  if (p === "anthropic") {
    return {
      key: "anthropic",
      label: "Anthropic 兼容",
      className: "border-amber-200 bg-amber-50 text-amber-700",
      dotColor: "bg-amber-500"
    };
  }
  if (n === "zhipu" || n === "volcengine") {
    return {
      key: "sdk",
      label: "官方 SDK",
      className: "border-indigo-200 bg-indigo-50 text-indigo-600",
      dotColor: "bg-indigo-500"
    };
  }
  return {
    key: "openai",
    label: "OpenAI 兼容",
    className: "border-sky-200 bg-sky-50 text-sky-700",
    dotColor: "bg-sky-500"
  };
}

// ==================== 扩展功能类型 ====================

/** 连通性检查结果 */
export interface ConnectivityResult {
  success: boolean;
  latencyMs?: number;
  error?: string;
}

/** 远程模型信息（从供应商 API 获取） */
export interface RemoteModelInfo {
  modelId: string;
  modelName: string;
  capabilities: string[];
  supportsThinking?: boolean;
  supportsMultimodal?: boolean;
  dimensions?: number[];
}

/** 远程模型列表响应 */
export interface FetchModelsResult {
  models: RemoteModelInfo[];
}

// ==================== 供应商 API ====================

export async function listProviders(): Promise<AiProvider[]> {
  return api.get<AiProvider[], AiProvider[]>("/ai-model-config/providers");
}

export async function getProvider(id: string): Promise<AiProvider> {
  return api.get<AiProvider, AiProvider>(`/ai-model-config/providers/${id}`);
}

export async function createProvider(payload: AiProviderPayload): Promise<string> {
  return api.post<string, string>("/ai-model-config/providers", payload);
}

export async function updateProvider(id: string, payload: AiProviderPayload): Promise<void> {
  await api.put(`/ai-model-config/providers/${id}`, payload);
}

export async function deleteProvider(id: string): Promise<void> {
  await api.delete(`/ai-model-config/providers/${id}`);
}

// ==================== 模型 API ====================

export async function listModels(capability?: string, includeDisabled?: boolean): Promise<AiModel[]> {
  const params = new URLSearchParams();
  if (capability) params.set("capability", capability);
  if (includeDisabled) params.set("includeDisabled", "true");
  const qs = params.toString();
  return api.get<AiModel[], AiModel[]>(`/ai-model-config/models${qs ? `?${qs}` : ""}`);
}

export async function getModel(id: string): Promise<AiModel> {
  return api.get<AiModel, AiModel>(`/ai-model-config/models/${id}`);
}

export async function createModel(payload: AiModelPayload): Promise<string> {
  return api.post<string, string>("/ai-model-config/models", payload);
}

export async function updateModel(id: string, payload: AiModelPayload): Promise<void> {
  await api.put(`/ai-model-config/models/${id}`, payload);
}

export async function deleteModel(id: string): Promise<void> {
  await api.delete(`/ai-model-config/models/${id}`);
}

// ==================== 默认模型 & 优先级 ====================

export async function setDefaultModel(id: string): Promise<void> {
  await api.patch(`/ai-model-config/models/${id}/set-default`);
}

export async function updatePriorities(items: ModelPriorityItem[]): Promise<void> {
  await api.patch("/ai-model-config/models/priorities", items);
}

// ==================== 连通性检查 & 远程模型 ====================

/**
 * 检查 AI 供应商的连通性
 * @param providerId 供应商 ID
 */
export async function checkConnectivity(providerId: string): Promise<ConnectivityResult> {
  return api.post<ConnectivityResult, ConnectivityResult>(
    `/ai-model-config/providers/${providerId}/check-connectivity`
  );
}

/**
 * 检查指定 AI 模型的连通性
 * @param modelId 模型 ID
 */
export async function checkModelConnectivity(modelId: string): Promise<ConnectivityResult> {
  return api.post<ConnectivityResult, ConnectivityResult>(
    `/ai-model-config/models/check-connectivity?id=${encodeURIComponent(modelId)}`
  );
}

/**
 * 从远程供应商拉取可用模型列表
 * @param providerId 供应商 ID
 */
export async function fetchRemoteModels(providerId: string): Promise<FetchModelsResult> {
  return api.post<FetchModelsResult, FetchModelsResult>(
    `/ai-model-config/providers/${providerId}/fetch-models`
  );
}

/**
 * 批量创建模型
 * @param payloads 模型创建请求列表
 */
export async function batchCreateModels(payloads: AiModelPayload[]): Promise<string[]> {
  return api.post<string[], string[]>("/ai-model-config/models/batch-create", payloads);
}

// ==================== 图标上传 ====================

/**
 * 上传供应商图标
 * @param providerId 供应商 ID
 * @param file 图标文件
 */
export async function uploadProviderIcon(providerId: string, file: File): Promise<{ iconUrl: string }> {
  const formData = new FormData();
  formData.append("file", file);
  return api.post<{ iconUrl: string }, { iconUrl: string }>(
    `/ai-model-config/providers/${providerId}/icon`,
    formData,
    { headers: { "Content-Type": "multipart/form-data" } }
  );
}
