import * as React from "react";
import { ArrowUpRight, BookOpen, Check, ImagePlus, Lightbulb, Loader2, Send, Square, X } from "lucide-react";

import { DeepThinkingSlider } from "@/components/chat/DeepThinkingSlider";
import { KnowledgeBaseSelector } from "@/components/chat/KnowledgeBaseSelector";
import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";

import { cn } from "@/lib/utils";
import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useChatStore } from "@/stores/chatStore";
import { storage } from "@/utils/storage";

const MAX_IMAGES = 10;
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

type PromptPreset = {
  id?: string;
  title: string;
  description: string;
  prompt: string;
  icon: React.ComponentType<{ className?: string }>;
};

const PRESET_ICONS = [BookOpen, Check, Lightbulb];

const DEFAULT_PRESETS: PromptPreset[] = [
  {
    title: "内容总结",
    description: "提炼 3-5 条关键信息与行动点",
    prompt: "请帮我总结以下内容，并列出3-5条要点：",
    icon: BookOpen
  },
  {
    title: "任务拆解",
    description: "把目标拆成可执行步骤与优先级",
    prompt: "请把下面需求拆解为步骤，并给出优先级和里程碑：",
    icon: Check
  },
  {
    title: "灵感扩展",
    description: "给出多个方案并比较优缺点",
    prompt: "围绕以下主题给出5-8个方案，并注明优缺点：",
    icon: Lightbulb
  }
];

interface UploadedImage {
  url: string;
  name: string;
  uploading: boolean;
  localUrl?: string;
  /** 预签名 HTTP URL（用于浏览器渲染） */
  previewUrl?: string;
}

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

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [images, setImages] = React.useState<UploadedImage[]>([]);
  const [promptPresets, setPromptPresets] = React.useState<PromptPreset[]>(DEFAULT_PRESETS);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration, knowledgeBaseIds, setKnowledgeBaseIds } =
    useChatStore();

  // ==================== 图片上传 ====================

  const uploadImages = async (files: File[]) => {
    const remaining = MAX_IMAGES - images.length;
    const toUpload = Math.min(files.length, remaining);
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
  };

  const handleImageSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;
    uploadImages(Array.from(files));
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  const handlePaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
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
    if (imageFiles.length > 0) {
      uploadImages(imageFiles);
    }
  };

  const removeImage = (index: number) => {
    setImages((prev) => prev.filter((_, i) => i !== index));
  };

  // ==================== 焦点 & 高度 ====================

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

  // ==================== 预设加载 ====================

  React.useEffect(() => {
    let active = true;

    const loadPresets = async () => {
      const data = await listSampleQuestions().catch(() => null);
      if (!active || !data || data.length === 0) {
        return;
      }
      const mapped = data
        .filter((item) => item.question && item.question.trim())
        .slice(0, 3)
        .map((item, index) => {
          const question = item.question.trim();
          const title =
            item.title?.trim() ||
            (question.length > 12 ? `${question.slice(0, 12)}...` : question) ||
            `推荐问法 ${index + 1}`;
          const description = item.description?.trim() || "直接点选即可开始对话";
          return {
            id: item.id,
            title,
            description,
            prompt: question,
            icon: PRESET_ICONS[index % PRESET_ICONS.length]
          };
        });
      if (mapped.length > 0) {
        setPromptPresets(mapped);
      }
    };

    loadPresets();
    return () => {
      active = false;
    };
  }, []);

  // ==================== 提交 ====================

  const applyPreset = React.useCallback(
    (prompt: string) => {
      if (isStreaming) return;
      setValue(prompt);
      focusInput();
    },
    [isStreaming, focusInput]
  );

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim() && images.length === 0) return;
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

  const hasContent = value.trim().length > 0 || images.length > 0;
  const uploadingCount = images.filter((img) => img.uploading).length;

  return (
    <div className="flex min-h-full flex-col items-center justify-center px-6 py-10">
      {/* Hero section */}
      <div
        className="w-full max-w-[640px] text-center opacity-0 animate-fade-up"
        style={{ animationFillMode: "both" }}
      >
        <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-600 shadow-lg shadow-indigo-200">
          <RAGStudioLogo className="h-6 w-6" />
        </div>
        <h1 className="mt-6 font-display text-[2rem] font-bold leading-tight tracking-tight text-gray-900 sm:text-[2.5rem]">
          你好，有什么可以帮你的？
        </h1>
        <p className="mt-3 text-[15px] leading-relaxed text-gray-500">
          基于企业知识库的 AI 智能助手，支持深度推理与分析
        </p>
      </div>

      {/* Preset cards */}
      <div
        className="mt-8 w-full max-w-[640px] opacity-0 animate-fade-up"
        style={{ animationDelay: "80ms", animationFillMode: "both" }}
      >
        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
          {promptPresets.map((preset) => {
            const Icon = preset.icon;
            return (
              <button
                key={preset.id ?? preset.title}
                type="button"
                onClick={() => applyPreset(preset.prompt)}
                disabled={isStreaming}
                className={cn(
                  "group relative flex flex-col items-start rounded-xl border border-gray-100 bg-white p-4 text-left transition-all duration-200 hover:border-indigo-100 hover:shadow-md hover:shadow-indigo-50",
                  isStreaming && "cursor-not-allowed opacity-60"
                )}
              >
                <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-50 text-indigo-500 transition-colors group-hover:bg-indigo-100">
                  <Icon className="h-4 w-4" />
                </span>
                <span className="mt-3 text-[13px] font-semibold text-gray-800">{preset.title}</span>
                <span className="mt-1 text-xs leading-relaxed text-gray-400">{preset.description}</span>
                <ArrowUpRight className="absolute top-3.5 right-3.5 h-3.5 w-3.5 text-gray-300 transition-colors group-hover:text-indigo-400" />
              </button>
            );
          })}
        </div>
      </div>

      {/* Input area */}
      <div
        className="mt-8 w-full max-w-[640px] opacity-0 animate-fade-up"
        style={{ animationDelay: "160ms", animationFillMode: "both" }}
      >
        <div className="mb-3">
          <KnowledgeBaseSelector
            selectedKnowledgeBaseIds={knowledgeBaseIds}
            onKnowledgeBaseIdsChange={setKnowledgeBaseIds}
          />
        </div>

        <div
          className={cn(
            "flex flex-col rounded-2xl border bg-white px-5 pt-4 pb-3 transition-all duration-200",
            isFocused
              ? "border-indigo-200 shadow-[0_0_0_3px_rgba(99,102,241,0.08),0_4px_16px_rgba(0,0,0,0.04)]"
              : "border-gray-200 shadow-[0_1px_4px_rgba(0,0,0,0.04)]"
          )}
        >
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            onPaste={handlePaste}
            placeholder={"输入你的问题..."}
            className="max-h-40 min-h-[64px] w-full resize-none border-0 bg-transparent px-0 py-1 text-[15px] text-gray-900 placeholder:text-gray-400 focus:outline-none"
            rows={2}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            onCompositionStart={() => {
              isComposingRef.current = true;
            }}
            onCompositionEnd={() => {
              isComposingRef.current = false;
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                const nativeEvent = event.nativeEvent as KeyboardEvent;
                if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) {
                  return;
                }
                event.preventDefault();
                handleSubmit();
              }
            }}
            aria-label="发送消息"
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
            </div>
          )}

          <div className="flex items-center gap-3 border-t border-gray-100 pt-3 mt-1">
            {/* 图片上传按钮 */}
            <div className="flex items-center gap-1">
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
                  <span className="text-[10px] text-gray-400">{images.length}/{MAX_IMAGES}</span>
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

            <DeepThinkingSlider />

            <div className="flex-1" />
            <p className="hidden text-[11px] text-gray-300 sm:block">
              <kbd className="text-gray-500">Enter</kbd> 发送 · <kbd className="text-gray-500">Shift+Enter</kbd> 换行
            </p>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={(uploadingCount > 0) || (!hasContent && !isStreaming)}
              aria-label={isStreaming ? "停止生成" : uploadingCount > 0 ? "上传中" : "发送消息"}
              className={cn(
                "inline-flex items-center justify-center rounded-full px-5 py-2.5 text-[13px] font-medium transition-all duration-200",
                isStreaming
                  ? "bg-rose-50 text-rose-500 hover:bg-rose-100"
                  : uploadingCount > 0
                    ? "bg-gray-100 text-gray-400 cursor-not-allowed"
                    : hasContent
                      ? "bg-indigo-600 text-white shadow-sm hover:bg-indigo-700 hover:shadow-md"
                      : "bg-gray-100 text-gray-400"
              )}
            >
              {isStreaming ? (
                <span className="inline-flex items-center gap-1.5"><Square className="h-3.5 w-3.5" /> 停止</span>
              ) : uploadingCount > 0 ? (
                <span className="inline-flex items-center gap-1.5"><Loader2 className="h-3.5 w-3.5 animate-spin" /> 上传中</span>
              ) : (
                <span className="inline-flex items-center gap-1.5"><Send className="h-3.5 w-3.5" /> 发送</span>
              )}
            </button>
          </div>
        </div>

        {isStreaming ? (
          <p className="mt-2 text-xs text-gray-400 animate-pulse-soft">生成中...</p>
        ) : null}
      </div>
    </div>
  );
}
