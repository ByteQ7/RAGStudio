import { api } from "@/services/api";

export interface McpServer {
  id: string;
  name: string;
  url: string;
  description?: string | null;
  enabled: number;
  transportType: string;
  headers?: string | null;
  lastStatus?: string | null;
  lastError?: string | null;
  lastCheckTime?: string | null;
  toolCount?: number | null;
  createdBy?: string | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface McpServerPayload {
  name?: string;
  url?: string;
  description?: string | null;
  enabled?: number;
  transportType?: string;
  headers?: string | null;
}

export interface HealthStatus {
  status: string;
  error?: string | null;
  toolCount: number;
}

export interface ConnectResult {
  success: boolean;
  toolCount: number;
  error?: string | null;
}

export async function listMcpServers(): Promise<McpServer[]> {
  return api.get<McpServer[], McpServer[]>("/mcp-server");
}

export async function getMcpServer(id: string): Promise<McpServer> {
  return api.get<McpServer, McpServer>(`/mcp-server/${id}`);
}

export async function createMcpServer(payload: McpServerPayload): Promise<string> {
  return api.post<string, string>("/mcp-server", payload);
}

export async function updateMcpServer(id: string, payload: McpServerPayload): Promise<void> {
  await api.put(`/mcp-server/${id}`, payload);
}

export async function deleteMcpServer(id: string): Promise<void> {
  await api.delete(`/mcp-server/${id}`);
}

export async function toggleMcpServer(id: string, enabled: boolean): Promise<void> {
  await api.put(`/mcp-server/${id}/toggle?enabled=${enabled}`);
}

export async function testMcpConnection(id: string): Promise<HealthStatus> {
  return api.post<HealthStatus, HealthStatus>(`/mcp-server/${id}/test`);
}

export async function reloadMcpServer(id: string): Promise<ConnectResult> {
  return api.post<ConnectResult, ConnectResult>(`/mcp-server/${id}/reload`);
}
