import { api } from "@/services/api";

export interface SkillSummary {
  name: string;
  description: string;
}

export async function listSkills(): Promise<SkillSummary[]> {
  return api.get<SkillSummary[], SkillSummary[]>("/admin/skills");
}

export async function reloadSkills(): Promise<void> {
  await api.post("/admin/skills/reload");
}
