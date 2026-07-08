import * as React from "react";
import { ImagePlus, Loader2, Send, Square, X } from "lucide-react";

import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { storage } from "@/utils/storage";

const MAX_IMAGES = 10;
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

interface UploadedImage {
  url: string;
  name: string;
  uploading: boolean;
  /** 本地预览 URL（上传期间使用 blob: URL 显示缩略图） */
  localUrl?: string;
  /** 预签名 HTTP URL（用于浏览器渲染） */
  previewUrl?: string;
}

// 上传单张图片到 S3，返回 { s3Url, previewUrl }
async function uploadImageToS3(file: File): Promise<{ s3Url: string; previewUrl: string }> {
  const token = storage.getToken();
  const headers: Record<string, string> = token ? { Authorization: token } : {};

  // 1. 上传到 S3
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

  // 2. 获取预签名 HTTP URL 用于前端显示
  const presignResp = await fetch(`${API_BASE_URL}/api/presign?url=${encodeURIComponent(s3Url)}`, { headers });
  if (!presignResp.ok) throw new Error("获取预签名 URL 失败");
  const presignData = await presignResp.json();
  const previewUrl = String(presignData.data || presignData);

  return { s3Url, previewUrl };
}

export function ChatInput() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [images, setImages] = React.useState<UploadedImage[]>([]);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    isStopping,
    cancelGeneration,
    inputFocusKey,
  } = useChatStore();

  // ==================== 从文件选择器上传 ====================

  const handleImageSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    const remaining = MAX_IMAGES - images.length;
    const toUpload = Math.min(files.length, remaining);
    if (toUpload <= 0) return;
    if (toUpload < files.length) {
      console.warn(`最多只能上传 ${MAX_IMAGES} 张图片，已跳过 ${files.length - toUpload} 张`);
    }

    for (let i = 0; i < toUpload; i++) {
      const file = files[i];
      if (!file.type.startsWith("image/")) continue;

      const localUrl = URL.createObjectURL(file);
      const placeholder: UploadedImage = { url: "", name: file.name, uploading: true, localUrl };
      setImages((prev) => [...prev, placeholder]);

      try {
        const { s3Url, previewUrl } = await uploadImageToS3(file);
        setImages((prev) => {
          const idx = prev.findLastIndex(
            (img) => img.name === file.name && img.uploading === true
          );
          if (idx === -1) return [...prev, { url: s3Url, name: file.name, uploading: false, localUrl, previewUrl }];
          const copy = [...prev];
          copy[idx] = { url: s3Url, name: file.name, uploading: false, localUrl, previewUrl };
          return copy;
        });
      } catch (err) {
        console.error("图片上传失败:", err);
        URL.revokeObjectURL(localUrl);
        setImages((prev) => prev.filter((img) => img !== placeholder));
      }
    }

    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  // ==================== 从剪贴板粘贴上传 ====================

  const handlePaste = async (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const items = e.clipboardData?.items;
    if (!items) return;

    const imageFiles: File[] = [];
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (item.type.startsWith("image/")) {
        const file = item.getAsFile();
        if (file) imageFiles.push(file);
      }
    }
    if (imageFiles.length === 0) return;

    // 剪贴板粘贴不阻止默认行为（文本仍可正常粘贴）
    const remaining = MAX_IMAGES - images.length;
    const toUpload = Math.min(imageFiles.length, remaining);
    for (let i = 0; i < toUpload; i++) {
      const file = imageFiles[i];
      const localUrl = URL.createObjectURL(file);
      const name = file.name || "clipboard.png";
      const placeholder: UploadedImage = { url: "", name, uploading: true, localUrl };
      setImages((prev) => [...prev, placeholder]);

      try {
        const { s3Url, previewUrl } = await uploadImageToS3(file);
        setImages((prev) => {
          const idx = prev.findLastIndex(
            (img) => img.name === name && img.uploading === true
          );
          if (idx === -1) return [...prev, { url: s3Url, name, uploading: false, localUrl, previewUrl }];
          const copy = [...prev];
          copy[idx] = { url: s3Url, name, uploading: false, localUrl, previewUrl };
          return copy;
        });
      } catch (err) {
        console.error("剪贴板图片上传失败:", err);
        URL.revokeObjectURL(localUrl);
        setImages((prev) => prev.filter((img) => img !== placeholder));
      }
    }
  };

  // ==================== 删除图片 ====================

  const removeImage = (index: number) => {
    setImages((prev) => prev.filter((_, i) => i !== index));
  };

  // ==================== 文本框焦点 & 高度 ====================

  const focusInput = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    const next = Math.min(el.scrollHeight, 160);
    el.style.height = `${next}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  React.useEffect(() => {
    if (!inputFocusKey) return;
    focusInput();
  }, [inputFocusKey, focusInput]);

  // ==================== 提交 ====================

  const handleSubmit = async () => {
    if (isStreaming || isStopping) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim() && images.length === 0) return;
    // 等待所有上传中的图片完成
    const uploading = images.filter((img) => img.uploading);
    if (uploading.length > 0) return;

    const next = value;
    // 发送 s3:// URL 给后端（持久化用），同时传 previewUrl 给前端显示
    const imageUrls = images.map((img) => img.url);
    const previewUrls = images.map((img) => img.previewUrl || img.url);
    setValue("");
    setImages([]);
    focusInput();
    await sendMessage(next, imageUrls.length > 0 ? imageUrls : undefined, previewUrls.length > 0 ? previewUrls : undefined);
    focusInput();
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      const nativeEvent = event.nativeEvent as KeyboardEvent;
      if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
        return;
      }
      event.preventDefault();
      handleSubmit();
    }
  };

  const hasContent = value.trim().length > 0 || images.length > 0;
  const uploadingCount = images.filter((img) => img.uploading).length;

  // ==================== 渲染 ====================

  return (
    <div className="space-y-2">
      <div
        className={cn(
          "flex flex-col rounded-2xl border bg-white px-4 pt-3.5 pb-2.5 transition-all duration-200",
          isFocused
            ? "border-indigo-200 shadow-[0_0_0_3px_rgba(99,102,241,0.08),0_2px_8px_rgba(0,0,0,0.04)]"
            : "border-gray-200 shadow-[0_1px_3px_rgba(0,0,0,0.04)]"
        )}
      >
        <Textarea
          ref={textareaRef}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onPaste={handlePaste}
          placeholder={"输入你的问题..."}
          className="max-h-40 min-h-[40px] w-full resize-none border-0 bg-transparent px-0 py-1 text-[15px] text-gray-900 shadow-none placeholder:text-gray-400 focus-visible:ring-0"
          rows={1}
          onFocus={() => setIsFocused(true)}
          onBlur={() => setIsFocused(false)}
          onCompositionStart={() => {
            isComposingRef.current = true;
          }}
          onCompositionEnd={() => {
            isComposingRef.current = false;
          }}
          onKeyDown={handleKeyDown}
          aria-label="聊天输入框"
        />

        {/* 图片预览 */}
        {images.length > 0 && (
          <div className="flex flex-wrap gap-2 pt-2">
            {images.map((img, idx) => (
              <div key={idx} className="relative group">
                {img.uploading ? (
                  <div className="flex items-center gap-1.5 rounded-lg bg-gray-50 px-3 py-2.5 text-xs text-gray-500 border border-gray-200">
                    <Loader2 className="h-3.5 w-3.5 animate-spin" />
                    <span>上传中...</span>
                  </div>
                ) : (
                  <div className="relative h-14 w-14">
                    <img
                      src={img.previewUrl || img.localUrl || img.url}
                      alt={img.name}
                      className="h-full w-full rounded-lg border border-gray-200 object-cover"
                    />
                    <span className="absolute -bottom-0.5 left-0 right-0 truncate px-1 text-[10px] text-white text-center leading-tight bg-black/40 rounded-b-lg">
                      {img.name}
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        if (img.localUrl) URL.revokeObjectURL(img.localUrl);
                        removeImage(idx);
                      }}
                      className="absolute -top-1.5 -right-1.5 flex h-4 w-4 items-center justify-center rounded-full bg-gray-700 text-white shadow-sm opacity-0 group-hover:opacity-100 transition-opacity"
                    >
                      <X className="h-3 w-3" />
                    </button>
                  </div>
                )}
              </div>
            ))}
            {images.length < MAX_IMAGES && (
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="flex h-14 w-14 items-center justify-center rounded-lg border-2 border-dashed border-gray-200 text-gray-300 hover:border-indigo-300 hover:text-indigo-400 transition-colors"
                title="继续添加图片"
              >
                <PlusIcon className="h-5 w-5" />
              </button>
            )}
          </div>
        )}

        {/* 底部工具栏 */}
        <div className="flex items-center gap-2 border-t border-gray-100 pt-2 mt-1.5">
          {/* 图片上传按钮 */}
          <div className="relative">
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={isStreaming || images.length >= MAX_IMAGES}
              className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-gray-400 hover:text-indigo-500 hover:bg-indigo-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              title={images.length >= MAX_IMAGES ? `最多 ${MAX_IMAGES} 张图片` : "上传图片 (或 Ctrl+V 粘贴)"}
            >
              <ImagePlus className="h-4 w-4" />
              <span className="hidden sm:inline">图片</span>
              {images.length > 0 && (
                <span className="text-[10px] text-gray-400">
                  {images.length}/{MAX_IMAGES}
                </span>
              )}
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              multiple
              className="hidden"
              onChange={handleImageSelect}
            />
          </div>

          <div className="ml-auto flex items-center gap-3">
            <span className="hidden text-[11px] text-gray-300 sm:inline">
              <kbd className="font-sans">Enter</kbd> 发送 · <kbd className="font-sans">Shift+Enter</kbd> 换行
            </span>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={(uploadingCount > 0) || (!hasContent && !isStreaming && !isStopping)}
              aria-label={isStopping ? "停止中" : isStreaming ? "停止生成" : uploadingCount > 0 ? "上传中" : "发送消息"}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full px-4 py-2 text-[13px] font-medium transition-all duration-200",
                isStopping
                  ? "bg-amber-50 text-amber-500"
                  : isStreaming
                    ? "bg-rose-50 text-rose-500 hover:bg-rose-100"
                    : uploadingCount > 0
                      ? "cursor-not-allowed bg-gray-100 text-gray-400"
                      : hasContent
                        ? "bg-indigo-600 text-white shadow-sm hover:bg-indigo-700 hover:shadow-md"
                        : "cursor-not-allowed bg-gray-100 text-gray-400"
              )}
            >
              {isStopping ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  <span>停止中</span>
                </>
              ) : isStreaming ? (
                <>
                  <Square className="h-3.5 w-3.5" />
                  <span>停止</span>
                </>
              ) : uploadingCount > 0 ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  <span>上传中</span>
                </>
              ) : (
                <>
                  <Send className="h-3.5 w-3.5" />
                  <span>发送</span>
                </>
              )}
            </button>
          </div>
        </div>
      </div>
      {isStreaming ? (
        <p className="text-center text-[11px] text-gray-400 animate-pulse-soft">生成中...</p>
      ) : null}
    </div>
  );
}

// 内联 Plus 图标（避免从 lucide 重复导入）
function PlusIcon({ className }: { className?: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </svg>
  );
}
