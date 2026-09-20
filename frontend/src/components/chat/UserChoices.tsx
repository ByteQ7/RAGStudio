import * as React from "react";
import { Check } from "lucide-react";

import { useChatStore } from "@/stores/chatStore";
import { cn } from "@/lib/utils";
import type { UserChoiceOption } from "@/types";

interface UserChoicesProps {
  options: UserChoiceOption[];
  /** 是否处于已选择状态 */
  disabled?: boolean;
  /** 从后续用户消息推断的已选项文本（历史回执态） */
  selectedText?: string;
}

/**
 * 用户选项选择组件
 *
 * 渲染 AI 提供的选项为可点击按钮（支持可选说明文字）。
 * 用户点击后，对应选项文本作为用户消息发送；已选择（含从历史推断）时渲染为只读回执。
 */
export function UserChoices({ options, disabled: externalDisabled, selectedText }: UserChoicesProps) {
  const [selectedIndex, setSelectedIndex] = React.useState<number | null>(null);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const isStreaming = useChatStore((s) => s.isStreaming);

  // 已选项来源：本地点击 > 历史推断
  const inferredIndex = React.useMemo(() => {
    if (!selectedText) return -1;
    return options.findIndex((opt) => opt.text.trim() === selectedText.trim());
  }, [options, selectedText]);

  const effectiveIndex = selectedIndex ?? (inferredIndex >= 0 ? inferredIndex : null);
  const disabled = externalDisabled || effectiveIndex !== null || isStreaming;

  const handleClick = async (index: number, text: string) => {
    if (disabled) return;
    setSelectedIndex(index);
    // 发送前等一小段时间确保动画展示
    await new Promise((r) => setTimeout(r, 200));
    await sendMessage(text);
  };

  return (
    <div className="mt-4 space-y-2" role="group" aria-label="请选择">
      <p className="text-xs font-medium text-[var(--color-text-secondary)]">请选择：</p>
      <div className="flex flex-wrap gap-2">
        {options.map((opt, idx) => {
          const isSelected = effectiveIndex === idx;
          const isDimmed = effectiveIndex !== null && !isSelected;
          return (
            <button
              key={idx}
              type="button"
              onClick={() => handleClick(idx, opt.text)}
              disabled={disabled}
              aria-pressed={isSelected}
              className={cn(
                "inline-flex flex-col items-start rounded-xl border px-4 py-2.5 text-left text-sm font-medium",
                "transition-all duration-200 motion-reduce:transition-none",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2",
                isSelected
                  ? "border-indigo-300 bg-indigo-50 text-indigo-700 shadow-sm dark:border-indigo-700 dark:bg-indigo-950/50 dark:text-indigo-300"
                  : isDimmed
                    ? "cursor-not-allowed border-[var(--color-border-secondary)] bg-[var(--color-fill-quaternary)] text-[var(--color-text-quaternary)] opacity-60"
                    : disabled
                      ? "cursor-not-allowed border-[var(--color-border-secondary)] bg-[var(--color-fill-tertiary)] text-[var(--color-text-tertiary)]"
                      : "border-[var(--color-border)] bg-[var(--color-bg-container)] text-[var(--color-text)] hover:border-indigo-300 hover:bg-indigo-50/60 hover:text-indigo-700 hover:shadow-sm active:scale-[0.98] dark:hover:border-indigo-700 dark:hover:bg-indigo-950/30 dark:hover:text-indigo-300"
              )}
            >
              <span className="inline-flex items-center gap-1.5">
                {isSelected ? <Check className="h-4 w-4 shrink-0" /> : null}
                {opt.text}
              </span>
              {opt.description ? (
                <span
                  className={cn(
                    "mt-0.5 text-xs font-normal",
                    isSelected ? "text-indigo-500 dark:text-indigo-400" : "text-[var(--color-text-tertiary)]"
                  )}
                >
                  {opt.description}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      {effectiveIndex !== null ? (
        <p role="status" aria-live="polite" className="text-xs text-[var(--color-text-tertiary)]">
          已选择：{options[effectiveIndex].text}
        </p>
      ) : null}
    </div>
  );
}
