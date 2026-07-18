import { api } from "@/services/api";

// ==================== 类型定义 ====================

export interface DefaultModelConfig {
  id: string;
  configKey: string;
  modelId: string;
  modelName: string;
  providerName: string;
  modelEnabled: boolean;
  hasApiKey: boolean;
}

// ==================== 默认模型配置 API ====================

/**
 * 查询所有场景的默认模型配置
 */
export async function listDefaults(): Promise<DefaultModelConfig[]> {
  return api.get<DefaultModelConfig[], DefaultModelConfig[]>("/ai-model-config/defaults");
}

/**
 * 更新指定场景的默认模型
 * @param configKey 配置键
 * @param modelId 新的模型 ID
 */
export async function updateDefault(configKey: string, modelId: string): Promise<void> {
  await api.put(`/ai-model-config/defaults/${configKey}`, { modelId });
}
