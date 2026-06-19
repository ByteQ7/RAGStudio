import * as React from "react";
import { ArrowUpRight, BookOpen, Check, Lightbulb, Send, Square } from "lucide-react";

import { KnowledgeBaseSelector } from "@/components/chat/KnowledgeBaseSelector";
import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";

import { cn } from "@/lib/utils";
import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useChatStore } from "@/stores/chatStore";

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

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [promptPresets, setPromptPresets] = React.useState<PromptPreset[]>(DEFAULT_PRESETS);
  const [knowledgeBaseIds, setKnowledgeBaseIds] = React.useState<string[]>([]);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration } =
    useChatStore();

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
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next, knowledgeBaseIds);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

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
          <div className="flex items-center gap-3 border-t border-gray-100 pt-3 mt-1">
              <div className="flex-1" />
            <p className="hidden text-[11px] text-gray-300 sm:block">
              <kbd className="text-gray-500">Enter</kbd> 发送 · <kbd className="text-gray-500">Shift+Enter</kbd> 换行
            </p>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!hasContent && !isStreaming}
              aria-label={isStreaming ? "停止生成" : "发送消息"}
              className={cn(
                "inline-flex items-center justify-center rounded-full px-5 py-2.5 text-[13px] font-medium transition-all duration-200",
                isStreaming
                  ? "bg-rose-50 text-rose-500 hover:bg-rose-100"
                  : hasContent
                    ? "bg-indigo-600 text-white shadow-sm hover:bg-indigo-700 hover:shadow-md"
                    : "bg-gray-100 text-gray-400"
              )}
            >
              {isStreaming ? (
                <span className="inline-flex items-center gap-1.5"><Square className="h-3.5 w-3.5" /> 停止</span>
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
