import { api } from "@/services/api";

// ==================== 类型定义 ====================

export interface AiProvider {
  id: string;
  name: string;
  displayName?: string | null;
  baseUrl: string;
  apiKey?: string | null;
  endpoints?: Record<string, string> | null;
  enabled: number;
  iconUrl?: string | null;
  modelCount?: number;
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
  dimension?: number | null;
  customUrl?: string | null;
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
  dimension?: number;
  customUrl?: string;
}

export interface ModelPriorityItem {
  id: string;
  priority: number;
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
  dimension?: number;
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

export async function listModels(capability?: string): Promise<AiModel[]> {
  const params = capability ? `?capability=${capability}` : "";
  return api.get<AiModel[], AiModel[]>(`/ai-model-config/models${params}`);
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
