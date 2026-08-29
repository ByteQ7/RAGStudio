import { api } from "./api";

export type PromptCategory = "chat" | "query" | "memory" | "graph" | "ingestion" | "tool";

export interface PromptConfig {
  key: string;
  category: PromptCategory;
  name: string;
  description?: string | null;
  content: string;
  defaultContent?: string | null;
  variables?: string | null;
  version: number;
  enabled: boolean;
  source: "db" | "classpath";
  customized?: boolean;
  updatedBy?: string | null;
  updateTime?: string | null;
}

export interface PromptHistory {
  version: number;
  content: string;
  updatedBy?: string | null;
  updateTime?: string | null;
}

export interface PromptUpdatePayload {
  name?: string;
  description?: string;
  content: string;
  enabled?: boolean;
}

/** 提示词全量列表（支持分类/关键字筛选） */
export async function getPrompts(params: { category?: string; keyword?: string } = {}): Promise<PromptConfig[]> {
  return api.get("/admin/prompts", { params });
}

/** 提示词详情（含出厂默认内容） */
export async function getPrompt(key: string): Promise<PromptConfig> {
  return api.get(`/admin/prompts/${key}`);
}

/** 更新提示词（写历史 + 版本 +1 + 热重载） */
export async function updatePrompt(key: string, payload: PromptUpdatePayload): Promise<PromptConfig> {
  return api.put(`/admin/prompts/${key}`, payload);
}

/** 重置为出厂默认（classpath 模板内容）并热重载 */
export async function resetPrompt(key: string): Promise<PromptConfig> {
  return api.post(`/admin/prompts/${key}/reset`);
}

/** 变更历史 */
export async function getPromptHistory(key: string): Promise<PromptHistory[]> {
  return api.get(`/admin/prompts/${key}/history`);
}

/** 回滚到指定版本 */
export async function rollbackPrompt(key: string, version: number): Promise<PromptConfig> {
  return api.post(`/admin/prompts/${key}/history/${version}/rollback`);
}

/** 试渲染（校验占位符与效果） */
export async function previewPrompt(key: string, slots: Record<string, string> = {}): Promise<string> {
  return api.post(`/admin/prompts/${key}/preview`, slots);
}