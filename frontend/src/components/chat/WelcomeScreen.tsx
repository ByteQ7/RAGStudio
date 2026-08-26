import * as React from "react";
import { ArrowUpRight, BookOpen, Check, ImagePlus, Lightbulb, Loader2, Send, Square, X } from "lucide-react";
import { toast } from "sonner";

import { DeepThinkingSlider } from "@/components/chat/DeepThinkingSlider";
import { KnowledgeBaseSelector } from "@/components/chat/KnowledgeBaseSelector";
import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";

import { cn } from "@/lib/utils";
import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useChatStore } from "@/stores/chatStore";
import {
  type UploadedImage,
  uploadImageToS3,
  revokeImageUrls,
  nextUploadId,
} from "@/utils/image";

const MAX_IMAGES = 10;

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

function createPlaceholder(file: File): UploadedImage {
  const _uploadId = nextUploadId();
  return { url: "", name: file.name, uploading: true, localUrl: URL.createObjectURL(file), _uploadId };
}

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [images, setImages] = React.useState<UploadedImage[]>([]);
  const [promptPresets, setPromptPresets] = React.useState<PromptPreset[]>(DEFAULT_PRESETS);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = React.useRef<HTMLInputElement | null>(null);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const isStreaming = useChatStore((s) => s.isStreaming);
  const cancelGeneration = useChatStore((s) => s.cancelGeneration);
  const knowledgeBaseIds = useChatStore((s) => s.knowledgeBaseIds);
  const setKnowledgeBaseIds = useChatStore((s) => s.setKnowledgeBaseIds);

  // ==================== 图片上传 ====================

  const uploadImages = async (files: File[]) => {
    const remaining = MAX_IMAGES - images.length;
    const toUpload = Math.min(files.length, remaining);
    const selected = files.slice(0, toUpload).filter((f) => f.type.startsWith("image/"));
    if (selected.length === 0) return;

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
    setImages((prev) => {
      const removed = prev[index];
      if (removed?.localUrl) URL.revokeObjectURL(removed.localUrl);
      return prev.filter((_, i) => i !== index);
    });
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
    const imageUrls = images.map((img) => img.url);
    const previewUrls = images.map((img) => img.previewUrl || img.url);
    setValue("");
    revokeImageUrls(images);
    setImages([]);
    focusInput();
    await sendMessage(next, imageUrls.length > 0 ? imageUrls : undefined, previewUrls.length > 0 ? previewUrls : undefined);
    focusInput();
  };

  const hasContent = value.trim().length > 0 || images.length > 0;
  const uploadingCount = images.filter((img) => img.uploading).length;

  return (
    <div className="flex min-h-full flex-col items-center justify-center px-6 py-10" style={{ background: 'var(--color-bg-layout)' }}>
      {/* Hero section */}
      <div
        className="w-[70%] text-center opacity-0 animate-fade-up"
        style={{ animationFillMode: "both" }}
      >
        <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-2xl" style={{ background: 'var(--color-fill-quaternary)' }}>
          <RAGStudioLogo className="h-6 w-6" style={{ color: 'hsl(var(--primary))' }} />
        </div>
        <h1 className="mt-6 font-display text-[2rem] font-bold leading-tight tracking-tight sm:text-[2.5rem]" style={{ color: 'var(--color-text)' }}>
          <span className="text-primary">你好，有什么可以帮你的？</span>
        </h1>
        <p className="mt-3 text-[18px] leading-relaxed" style={{ color: 'var(--color-text-secondary)' }}>
          基于企业知识库的 AI 智能助手，支持深度推理与分析
        </p>
      </div>

      {/* Preset cards */}
      <div
        className="mt-8 w-[70%] opacity-0 animate-fade-up"
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
                  "group relative flex flex-col items-start rounded-xl border p-4 text-left transition-all duration-150",
                  isStreaming && "cursor-not-allowed opacity-60"
                )}
                style={{
                  borderColor: 'var(--color-border-secondary)',
                  background: 'var(--color-bg-container)'
                }}
              >
                <span className="flex h-9 w-9 items-center justify-center rounded-lg transition-colors" style={{ background: 'var(--color-fill-quaternary)', color: 'hsl(var(--primary))' }}>
                  <Icon className="h-4 w-4" />
                </span>
                <span className="mt-3 text-[14px] font-semibold" style={{ color: 'var(--color-text)' }}>{preset.title}</span>
                <span className="mt-1 text-xs leading-relaxed" style={{ color: 'var(--color-text-tertiary)' }}>{preset.description}</span>
                <ArrowUpRight className="absolute top-3.5 right-3.5 h-3.5 w-3.5" style={{ color: 'var(--color-text-tertiary)' }} />
              </button>
            );
          })}
        </div>
      </div>

      {/* Input area */}
      <div
        className="mt-8 w-[70%] opacity-0 animate-fade-up"
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
            "flex flex-col rounded-2xl px-5 pt-4 pb-3 transition-all duration-150 border",
            "bg-[var(--color-bg-elevated)] backdrop-blur-xl backdrop-saturate-150"
          )}
          style={{
            borderColor: isFocused ? 'var(--color-border)' : 'var(--color-border-secondary)',
            boxShadow: isFocused ? 'var(--shadow-md)' : 'var(--shadow-sm)'
          }}
        >
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            onPaste={handlePaste}
            placeholder={"输入你的问题..."}
            className="max-h-40 min-h-[64px] w-full resize-none border-0 bg-transparent px-0 py-1 text-[18px] focus:outline-none"
            style={{ color: 'var(--color-text)' }}
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
                    <div className="flex items-center gap-1.5 rounded-lg px-3 py-2.5 text-xs border"
                      style={{ background: 'var(--color-bg-container-secondary)', color: 'var(--color-text-secondary)', borderColor: 'var(--color-border-secondary)' }}>
                      <Loader2 className="h-3.5 w-3.5 animate-spin" />
                      <span>上传中...</span>
                    </div>
                  ) : (
                    <div className="relative h-14 w-14">
                      <img
                        src={img.previewUrl || img.localUrl || img.url}
                        alt={img.name}
                        className="h-full w-full rounded-lg object-cover"
                        style={{ borderColor: 'var(--color-border-secondary)', borderWidth: 1, borderStyle: 'solid' }}
                      />
                      <span className="absolute -bottom-0.5 left-0 right-0 truncate px-1 text-[11px] text-white text-center leading-tight bg-black/40 rounded-b-lg">
                        {img.name}
                      </span>
                      <button
                        type="button"
                        onClick={() => removeImage(idx)}
                        className="absolute -top-1.5 -right-1.5 flex h-4 w-4 items-center justify-center rounded-full text-white shadow-sm opacity-0 group-hover:opacity-100 transition-opacity"
                        style={{ background: 'var(--color-bg-spotlight)' }}
                      >
                        <X className="h-3 w-3" />
                      </button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          <div className="flex items-center gap-3" style={{ borderTopColor: 'var(--color-border-secondary)', borderTopWidth: 1, borderStyle: 'solid' }}>
            <div className="pt-3 mt-1 flex items-center gap-3 flex-1">
              {/* 图片上传按钮 */}
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={isStreaming || images.length >= MAX_IMAGES}
                  className="flex items-center gap-1 rounded-lg px-2 py-1.5 text-xs transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  style={{ color: 'var(--color-text-tertiary)' }}
                  title={images.length >= MAX_IMAGES ? `最多 ${MAX_IMAGES} 张图片` : "上传图片 (或 Ctrl+V 粘贴)"}
                >
                  <ImagePlus className="h-4 w-4" />
                  <span className="hidden sm:inline">图片</span>
                  {images.length > 0 && (
                    <span className="text-[11px]" style={{ color: 'var(--color-text-tertiary)' }}>{images.length}/{MAX_IMAGES}</span>
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
              <p className="hidden text-[12px] sm:block" style={{ color: 'var(--color-text-tertiary)' }}>
                <kbd className="font-mono" style={{ color: 'var(--color-text-secondary)' }}>Enter</kbd> 发送 · <kbd className="font-mono" style={{ color: 'var(--color-text-secondary)' }}>Shift+Enter</kbd> 换行
              </p>
              <button
                type="button"
                onClick={handleSubmit}
                disabled={(uploadingCount > 0) || (!hasContent && !isStreaming)}
                aria-label={isStreaming ? "停止生成" : uploadingCount > 0 ? "上传中" : "发送消息"}
                className={cn(
                  "inline-flex items-center justify-center rounded-full px-5 py-2.5 text-[14px] font-medium transition-all duration-200",
                  isStreaming
                    ? ""
                    : uploadingCount > 0
                      ? "cursor-not-allowed"
                      : ""
                )}
                style={{
                  background: isStreaming
                    ? 'var(--color-fill-quaternary)'
                    : uploadingCount > 0
                      ? 'var(--color-fill-tertiary)'
                      : hasContent
                        ? 'hsl(var(--primary))'
                        : 'var(--color-fill-tertiary)',
                  color: isStreaming
                    ? 'hsl(var(--primary))'
                    : uploadingCount > 0
                      ? 'var(--color-text-tertiary)'
                      : hasContent
                        ? 'white'
                        : 'var(--color-text-tertiary)'
                }}
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
        </div>

        {isStreaming ? (
          <p className="mt-2 text-xs animate-pulse-soft" style={{ color: 'var(--color-text-tertiary)' }}>生成中...</p>
        ) : null}
      </div>
    </div>
  );
}
