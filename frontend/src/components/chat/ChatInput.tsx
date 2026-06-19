import * as React from "react";
import { Send, Square } from "lucide-react";

import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

interface ChatInputProps {
  knowledgeBaseIds?: string[];
}

export function ChatInput({ knowledgeBaseIds }: ChatInputProps) {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    inputFocusKey
  } = useChatStore();

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
          aria-label="聊天输入框"
        />
        <div className="flex items-center gap-2 border-t border-gray-100 pt-2 mt-1.5">

          <div className="ml-auto flex items-center gap-3">
            <span className="hidden text-[11px] text-gray-300 sm:inline">
              <kbd className="font-sans">Enter</kbd> 发送 · <kbd className="font-sans">Shift+Enter</kbd> 换行
            </span>
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!hasContent && !isStreaming}
              aria-label={isStreaming ? "停止生成" : "发送消息"}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full px-4 py-2 text-[13px] font-medium transition-all duration-200",
                isStreaming
                  ? "bg-rose-50 text-rose-500 hover:bg-rose-100"
                  : hasContent
                    ? "bg-indigo-600 text-white shadow-sm hover:bg-indigo-700 hover:shadow-md"
                    : "cursor-not-allowed bg-gray-100 text-gray-400"
              )}
            >
              {isStreaming ? (
                <>
                  <Square className="h-3.5 w-3.5" />
                  <span>停止</span>
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
