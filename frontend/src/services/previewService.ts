import { api, API_BASE_URL } from "@/services/api";
import { storage } from "@/utils/storage";

export interface PreviewData {
  previewUrl: string;
  contentUrl: string | null;
  expiresIn: number;
  fileName: string;
  fileType: string;
  fileSize: number;
  isText: boolean;
}

export async function getDocumentPreviewUrl(docId: string): Promise<PreviewData> {
  return api.post(`/knowledge-base/docs/${docId}/preview`);
}

export async function getDocumentBinaryContent(docId: string): Promise<ArrayBuffer> {
  const headers: Record<string, string> = {};
  const token = storage.getToken();
  if (token) {
    headers["Authorization"] = token;
  }

  const resp = await fetch(`${API_BASE_URL}/knowledge-base/docs/${docId}/preview/file`, { headers });
  if (!resp.ok) throw new Error("获取文档内容失败");

  const contentType = resp.headers.get("content-type") || "";
  if (contentType.includes("json")) {
    const text = await resp.text();
    const json = JSON.parse(text);
    throw new Error(json?.message || "获取文档内容失败");
  }

  return resp.arrayBuffer();
}
