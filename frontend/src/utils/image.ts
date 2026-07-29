import { storage } from "@/utils/storage";
import { API_BASE_URL } from "@/services/api";

export interface UploadedImage {
  url: string;
  name: string;
  uploading: boolean;
  localUrl?: string;
  previewUrl?: string;
  /** 用于跟踪的唯一 ID */
  _uploadId: number;
}

let uploadIdCounter = 0;
export function nextUploadId(): number {
  return ++uploadIdCounter;
}

export async function uploadImageToS3(file: File): Promise<{ s3Url: string; previewUrl: string }> {
  const token = storage.getToken();
  const headers: Record<string, string> = token ? { Authorization: token } : {};

  const formData = new FormData();
  formData.append("file", file);
  const uploadResp = await fetch(`${API_BASE_URL}/rag/v3/upload-image`, {
    method: "POST",
    headers,
    body: formData,
  });
  if (!uploadResp.ok) throw new Error("图片上传失败");
  const uploadData = await uploadResp.json();
  const s3Url = String(uploadData.data || uploadData);

  const presignResp = await fetch(`${API_BASE_URL}/presign?url=${encodeURIComponent(s3Url)}`, { headers });
  if (!presignResp.ok) throw new Error("获取预签名 URL 失败");
  const presignData = await presignResp.json();
  const previewUrl = String(presignData.data || presignData);

  return { s3Url, previewUrl };
}

export function revokeImageUrls(images: UploadedImage[]) {
  for (const img of images) {
    if (img.localUrl) {
      URL.revokeObjectURL(img.localUrl);
    }
  }
}

export async function getPresignedUrl(s3Url: string): Promise<string> {
  const token = storage.getToken();
  const headers: Record<string, string> = token ? { Authorization: token } : {};
  const resp = await fetch(`${API_BASE_URL}/presign?url=${encodeURIComponent(s3Url)}`, { headers });
  if (!resp.ok) throw new Error("获取预签名 URL 失败");
  const data = await resp.json();
  return String(data.data || data);
}
