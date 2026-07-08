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
