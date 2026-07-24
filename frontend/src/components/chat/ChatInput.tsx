import * as React from "react";
import { ImagePlus, Loader2, Send, Square, X } from "lucide-react";
import { toast } from "sonner";

import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { DeepThinkingSlider } from "@/components/chat/DeepThinkingSlider";
import {
  type UploadedImage,
  uploadImageToS3,
  revokeImageUrls,
  nextUploadId,
} from "@/utils/image";

const MAX_IMAGES = 10;

function createPlaceholder(file: File): UploadedImage {
  const _uploadId = nextUploadId();
  return { url: "", name: file.name, uploading: true, localUrl: URL.createObjectURL(file), _uploadId };
}

export function ChatInput() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [images, setImages] = React.useState<UploadedImage[]>([]);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const isStreaming = useChatStore((s) => s.isStreaming);
  const isStopping = useChatStore((s) => s.isStopping);
  const cancelGeneration = useChatStore((s) => s.cancelGeneration);
  const inputFocusKey = useChatStore((s) => s.inputFocusKey);

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

    const selected = Array.from(files).slice(0, toUpload).filter((f) => f.type.startsWith("image/"));

    for (const file of selected) {
      const placeholder = createPlaceholder(file);
      setImages((prev) => [...prev, placeholder]);

      try {
        const { s3Url, previewUrl } = await uploadImageToS3(file);
        setImages((prev) => {
          const idx = prev.findIndex((img) => img._uploadId === placeholder._uploadId);
          if (idx === -1) return [...prev, { ...placeholder, url: s3Url, previewUrl, uploading: false }];
          const copy = [...prev];
          copy[idx] = { ...copy[idx], url: s3Url, previewUrl, uploading: false };
          return copy;
        });
      } catch (err) {
        console.error("图片上传失败:", err);
        toast.error(`图片 ${file.name} 上传失败`);
        URL.revokeObjectURL(placeholder.localUrl!);
        setImages((prev) => prev.filter((img) => img._uploadId !== placeholder._uploadId));
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

    const remaining = MAX_IMAGES - images.length;
    const toUpload = Math.min(imageFiles.length, remaining);
    const selected = imageFiles.slice(0, toUpload);

    for (const file of selected) {
      const placeholder = createPlaceholder(file);
      setImages((prev) => [...prev, placeholder]);

      try {
        const { s3Url, previewUrl } = await uploadImageToS3(file);
        setImages((prev) => {
          const idx = prev.findIndex((img) => img._uploadId === placeholder._uploadId);
          if (idx === -1) return [...prev, { ...placeholder, url: s3Url, previewUrl, uploading: false }];
          const copy = [...prev];
          copy[idx] = { ...copy[idx], url: s3Url, previewUrl, uploading: false };
          return copy;
        });
      } catch (err) {
        console.error("剪贴板图片上传失败:", err);
        toast.error(`剪贴板图片上传失败`);
        URL.revokeObjectURL(placeholder.localUrl!);
        setImages((prev) => prev.filter((img) => img._uploadId !== placeholder._uploadId));
      }
    }
  };

  // ==================== 删除图片 ====================

  const removeImage = (index: number) => {
    setImages((prev) => {
      const removed = prev[index];
      if (removed?.localUrl) URL.revokeObjectURL(removed.localUrl);
      return prev.filter((_, i) => i !== index);
    });
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
    const imageUrls = images.map((img) => img.url);
    const previewUrls = images.map((img) => img.previewUrl || img.url);
    setValue("");
    revokeImageUrls(images);
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
          "flex flex-col rounded-2xl px-4 pt-3.5 pb-2.5 transition-all duration-200",
          isFocused
            ? "border-indigo-200/70 shadow-[0_0_0_3px_rgba(99,102,241,0.08),0_4px_16px_rgba(0,0,0,0.04)]"
            : "border-gray-200/60 shadow-[0_1px_4px_rgba(0,0,0,0.02),0_4px_12px_rgba(0,0,0,0.02)]",
          "bg-white/90 backdrop-blur-xl backdrop-saturate-150 border"
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
                        onClick={() => removeImage(idx)}
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

          {/* 深度思考滑块 */}
          <DeepThinkingSlider />

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
