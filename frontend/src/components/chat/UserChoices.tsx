import * as React from "react";
import { useChatStore } from "@/stores/chatStore";
import type { UserChoiceOption } from "@/types";

interface UserChoicesProps {
  options: UserChoiceOption[];
  /** 是否处于已选择状态 */
  disabled?: boolean;
}

/**
 * 用户选项选择组件
 *
 * 渲染 AI 提供的选项为可点击按钮。
 * 用户点击后，对应选项文本作为用户消息发送。
 */
export function UserChoices({ options, disabled: externalDisabled }: UserChoicesProps) {
  const [selectedIndex, setSelectedIndex] = React.useState<number | null>(null);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const isStreaming = useChatStore((s) => s.isStreaming);

  const disabled = externalDisabled || selectedIndex !== null || isStreaming;

  const handleClick = async (index: number, text: string) => {
    if (disabled) return;
    setSelectedIndex(index);
    // 发送前等一小段时间确保动画展示
    await new Promise((r) => setTimeout(r, 200));
    await sendMessage(text);
  };

  return (
    <div className="mt-4 space-y-2" role="group" aria-label="请选择">
      <p className="text-xs font-medium text-gray-500">请选择：</p>
      <div className="flex flex-wrap gap-2">
        {options.map((opt, idx) => (
          <button
            key={idx}
            type="button"
            onClick={() => handleClick(idx, opt.text)}
            disabled={disabled}
            className={`
              inline-flex items-center rounded-xl border px-4 py-2.5 text-sm font-medium
              transition-all duration-200
              ${
                selectedIndex === idx
                  ? "border-indigo-300 bg-indigo-50 text-indigo-700 shadow-sm"
                  : disabled
                    ? "cursor-not-allowed border-gray-200 bg-gray-50 text-gray-400"
                    : "border-gray-200 bg-white text-gray-700 hover:border-indigo-200 hover:bg-indigo-50 hover:text-indigo-600 hover:shadow-sm active:scale-[0.98]"
              }
            `}
          >
            {selectedIndex === idx ? (
              <>
                <svg
                  className="mr-1.5 h-4 w-4 shrink-0"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                >
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                {opt.text}
              </>
            ) : (
              opt.text
            )}
          </button>
        ))}
      </div>
    </div>
  );
}
