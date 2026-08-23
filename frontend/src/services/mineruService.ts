import { api } from "@/services/api";

export interface MineruEndpointVO {
  enabled?: boolean;
  baseUrl?: string | null;
  backend?: string;
  lang?: string;
  apiKey?: string | null;
  /** 连通性探测结果（仅探测接口返回） */
  reachable?: boolean | null;
}

export interface MineruConfigVO {
  local?: MineruEndpointVO;
  remote?: MineruEndpointVO;
  timeoutSeconds?: number;
  minTextLength?: number;
}

export async function getMineruConfig(): Promise<MineruConfigVO> {
  return api.get<MineruConfigVO, MineruConfigVO>("/rag/mineru/config");
}

export async function updateMineruConfig(vo: MineruConfigVO): Promise<void> {
  await api.put("/rag/mineru/config", vo);
}

export async function pingMineru(): Promise<MineruConfigVO> {
  return api.post<MineruConfigVO, MineruConfigVO>("/rag/mineru/config/ping");
}