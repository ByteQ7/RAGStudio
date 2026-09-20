import { api } from "@/services/api";

export interface WorkflowStep {
  action: string;
  tool?: string | null;
  when?: string | null;
}

/** 画布节点类型 */
export type WorkflowNodeKind = "start" | "step" | "condition" | "end";

export interface WorkflowGraphNodeData {
  action?: string | null;
  tool?: string | null;
  when?: string | null;
  expression?: string | null;
}

export interface WorkflowGraphNode {
  id: string;
  kind: WorkflowNodeKind;
  x?: number | null;
  y?: number | null;
  data: WorkflowGraphNodeData;
}

export interface WorkflowGraphEdge {
  id: string;
  source: string;
  target: string;
  label?: string | null;
}

/** 工作流图（画布编辑态，见 docs/workflow-canvas-design.md） */
export interface WorkflowGraph {
  version?: number;
  nodes: WorkflowGraphNode[];
  edges: WorkflowGraphEdge[];
}

/** 图校验结果 */
export interface WorkflowGraphValidation {
  errors: string[];
  warnings: string[];
}

export interface WorkflowListItem {
  id: number;
  name: string;
  title: string;
  description: string;
  source: string;
  enabled: boolean;
  stepCount: number;
  changeLog: string | null;
  updatedBy: string | null;
  updateTime: string | null;
  indexed: boolean;
}

export interface WorkflowDetail {
  id: number;
  name: string;
  title: string;
  description: string;
  steps: WorkflowStep[];
  notes: string | null;
  graph: WorkflowGraph | null;
  source: string;
  enabled: boolean;
  changeLog: string | null;
  updatedBy: string | null;
  createTime: string | null;
  updateTime: string | null;
}

export interface WorkflowSavePayload {
  name?: string;
  title: string;
  description: string;
  steps?: WorkflowStep[];
  notes?: string | null;
  changeLog?: string | null;
  /** 画布图；提供时服务端以图编译出的 steps 为准 */
  graph?: WorkflowGraph | null;
}

export async function listWorkflows(): Promise<WorkflowListItem[]> {
  return api.get<WorkflowListItem[], WorkflowListItem[]>("/admin/workflows");
}

export async function getWorkflow(name: string): Promise<WorkflowDetail> {
  return api.get<WorkflowDetail, WorkflowDetail>(`/admin/workflows/${encodeURIComponent(name)}`);
}

export async function createWorkflow(payload: WorkflowSavePayload): Promise<WorkflowDetail> {
  return api.post<WorkflowDetail, WorkflowDetail>("/admin/workflows", payload);
}

export async function updateWorkflow(
  name: string,
  payload: WorkflowSavePayload
): Promise<WorkflowDetail> {
  return api.put<WorkflowDetail, WorkflowDetail>(
    `/admin/workflows/${encodeURIComponent(name)}`,
    payload
  );
}

export async function deleteWorkflow(name: string): Promise<void> {
  return api.delete<void, void>(`/admin/workflows/${encodeURIComponent(name)}`);
}

export async function toggleWorkflow(name: string, enabled: boolean): Promise<WorkflowDetail> {
  return api.post<WorkflowDetail, WorkflowDetail>(
    `/admin/workflows/${encodeURIComponent(name)}/toggle`,
    { enabled }
  );
}

export async function rebuildWorkflowIndex(): Promise<number> {
  return api.post<number, number>("/admin/workflows/rebuild-index");
}

/** 仅校验画布图（画布「校验」按钮），不落库 */
export async function validateWorkflowGraph(
  graph: WorkflowGraph
): Promise<WorkflowGraphValidation> {
  return api.post<WorkflowGraphValidation, WorkflowGraphValidation>("/admin/workflows/validate", {
    graph
  });
}
