import { api } from "./api";

export interface GraphOverview {
  graphEnabled: boolean;
  entityCount: number;
  relationCount: number;
  extractionCount: number;
  failedCount: number;
  lastBuildTime?: string | null;
}

export interface GraphEntity {
  id: string;
  canonicalName: string;
  displayName: string;
  entityType: string;
  description?: string | null;
  aliases?: string[];
  relationCount?: number;
  createTime?: string | null;
}

export interface GraphBuildLog {
  id: string;
  triggerType?: string | null;
  docId?: string | null;
  status?: string | null;
  entityAdded?: number;
  entityMerged?: number;
  relationAdded?: number;
  relationRemoved?: number;
  llmCalls?: number;
  durationMs?: number | null;
  errorMessage?: string | null;
  createTime?: string | null;
}

export interface GraphSubgraphNode {
  id: string;
  name: string;
  type: string;
}

export interface GraphSubgraphLink {
  source: string;
  target: string;
  predicate: string;
}

export interface GraphSubgraph {
  nodes: GraphSubgraphNode[];
  links: GraphSubgraphLink[];
  truncated?: boolean;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

/** 获取图谱统计概览 */
export async function getGraphOverview(kbId: string): Promise<GraphOverview> {
  return api.get(`/admin/graph/kb/${kbId}/overview`);
}

/** 触发知识库图谱全量重建（异步） */
export async function rebuildGraph(kbId: string): Promise<string> {
  return api.post(`/admin/graph/kb/${kbId}/rebuild`);
}

/** 实体分页查询 */
export async function getGraphEntities(
  kbId: string,
  params: { keyword?: string; entityType?: string; current?: number; size?: number } = {}
): Promise<PageResult<GraphEntity>> {
  return api.get(`/admin/graph/kb/${kbId}/entities`, { params });
}

/** 合并实体 */
export async function mergeGraphEntities(kbId: string, keepEntityId: string, mergeEntityIds: string[]): Promise<void> {
  return api.post("/admin/graph/entities/merge", { kbId, keepEntityId, mergeEntityIds });
}

/** 子图可视化数据（mermaid 渲染） */
export async function getGraphSubgraph(
  kbId: string,
  params: { focusEntityId?: string; maxNodes?: number } = {}
): Promise<GraphSubgraph> {
  return api.get(`/admin/graph/kb/${kbId}/graph`, { params });
}

/** 构建日志分页 */
export async function getGraphBuildLogs(kbId: string, current = 1, size = 20): Promise<PageResult<GraphBuildLog>> {
  return api.get(`/admin/graph/kb/${kbId}/build-logs`, { params: { current, size } });
}