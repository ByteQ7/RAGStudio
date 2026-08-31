import { api } from "@/services/api";

export type SkillSyncState = "SYNCED" | "PENDING_SYNC" | "DRIFTED" | "UNMANAGED" | "RUNTIME_ONLY";

export interface SkillListItem {
  name: string;
  description: string;
  skillType: string | null;
  currentVersion: number | null;
  declaredVersion: string | null;
  enabled: boolean | null;
  changeLog: string | null;
  updatedBy: string | null;
  updateTime: string | null;
  syncState: SkillSyncState;
  loaded: boolean | null;
  errors: string;
  warnings: string;
}

export interface SkillFileEntry {
  path: string;
  isBinary: boolean;
  size: number;
}

export interface SkillDetail extends SkillListItem {
  manifest: Record<string, unknown> | null;
  files: SkillFileEntry[];
}

export interface SkillVersionInfo {
  version: number;
  changeLog: string | null;
  fileCount: number;
  totalSize: number;
  createdBy: string | null;
  createTime: string | null;
  current: boolean;
}

export interface SkillFileContent {
  path: string;
  isBinary: boolean;
  size: number;
  content: string;
}

export type SkillDiffStatus = "added" | "deleted" | "modified" | "unchanged";

export interface SkillDiffFile {
  path: string;
  status: SkillDiffStatus;
  isBinary: boolean;
  oldSize: number | null;
  newSize: number | null;
}

export interface SkillDiffResult {
  fromVersion: number;
  toVersion: number;
  added: number;
  deleted: number;
  modified: number;
  unchanged: number;
  manifestChanges: string[];
  files: SkillDiffFile[];
}

export interface SkillCommitPayload {
  changeLog: string;
  upserts: Record<string, string>;
  deletions: string[];
}

export async function listSkills(): Promise<SkillListItem[]> {
  return api.get<SkillListItem[], SkillListItem[]>("/admin/skills");
}

export async function getSkill(name: string): Promise<SkillDetail> {
  return api.get<SkillDetail, SkillDetail>(`/admin/skills/${name}`);
}

export async function listSkillVersions(name: string): Promise<SkillVersionInfo[]> {
  return api.get<SkillVersionInfo[], SkillVersionInfo[]>(`/admin/skills/${name}/versions`);
}

export async function getSkillVersionFile(
  name: string,
  version: number,
  path: string
): Promise<SkillFileContent> {
  return api.get<SkillFileContent, SkillFileContent>(
    `/admin/skills/${name}/versions/${version}/file?path=${encodeURIComponent(path)}`
  );
}

export async function getSkillDiff(
  name: string,
  from: number,
  to: number
): Promise<SkillDiffResult> {
  return api.get<SkillDiffResult, SkillDiffResult>(
    `/admin/skills/${name}/diff?from=${from}&to=${to}`
  );
}

export async function createSkillBlank(payload: {
  name: string;
  description: string;
}): Promise<SkillDetail> {
  return api.post<SkillDetail, SkillDetail>("/admin/skills/blank", payload);
}

export async function createSkillZip(file: File): Promise<SkillDetail> {
  const form = new FormData();
  form.append("file", file);
  return api.post<SkillDetail, SkillDetail>("/admin/skills", form);
}

export async function uploadSkillVersion(name: string, file: File): Promise<SkillDetail> {
  const form = new FormData();
  form.append("file", file);
  return api.post<SkillDetail, SkillDetail>(`/admin/skills/${name}/versions`, form);
}

export async function previewSkillVersionZip(name: string, file: File): Promise<SkillDiffResult> {
  const form = new FormData();
  form.append("file", file);
  return api.post<SkillDiffResult, SkillDiffResult>(`/admin/skills/${name}/versions/preview`, form);
}

export async function commitSkill(name: string, payload: SkillCommitPayload): Promise<SkillDetail> {
  return api.post<SkillDetail, SkillDetail>(`/admin/skills/${name}/commit`, payload);
}

export async function rollbackSkill(name: string, version: number): Promise<SkillDetail> {
  return api.post<SkillDetail, SkillDetail>(`/admin/skills/${name}/rollback/${version}`);
}

export async function enableSkill(name: string): Promise<SkillDetail> {
  return api.post<SkillDetail, SkillDetail>(`/admin/skills/${name}/enable`);
}

export async function disableSkill(name: string): Promise<SkillDetail> {
  return api.post<SkillDetail, SkillDetail>(`/admin/skills/${name}/disable`);
}

export async function deleteSkill(name: string): Promise<void> {
  await api.delete(`/admin/skills/${name}`);
}

export async function importSkill(name: string): Promise<SkillDetail> {
  return api.post<SkillDetail, SkillDetail>(`/admin/skills/${name}/import`);
}

export async function syncSkill(name: string): Promise<SkillDetail> {
  return api.post<SkillDetail, SkillDetail>(`/admin/skills/${name}/sync`);
}

export async function reloadSkills(): Promise<void> {
  await api.post("/admin/skills/reload");
}
