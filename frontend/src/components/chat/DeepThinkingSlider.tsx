import { useRef, useState, useEffect } from "react";
import { Brain, ChevronDown } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

/**
 * 深度思考滑块组件
 * <p>提供统一的 0-100 滑块，0 = 关闭思考，1-100 = 思考深度。</p>
 */
export function DeepThinkingSlider() {
  const deepThinkingLevel = useChatStore((s) => s.deepThinkingLevel);
  const setDeepThinkingLevel = useChatStore((s) => s.setDeepThinkingLevel);
  const isStreaming = useChatStore((s) => s.isStreaming);
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);

  // 点击外部关闭
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (
        panelRef.current &&
        !panelRef.current.contains(e.target as Node) &&
        btnRef.current &&
        !btnRef.current.contains(e.target as Node)
      ) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [open]);

  const progress = deepThinkingLevel / 100;

  return (
    <div className="relative">
      <button
        ref={btnRef}
        type="button"
        onClick={() => !isStreaming && setOpen(!open)}
        disabled={isStreaming}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-all duration-200",
          deepThinkingLevel > 0
            ? "bg-gradient-to-r from-amber-50 to-orange-50 text-amber-700 border border-amber-200/60 shadow-sm"
            : "text-gray-400 hover:text-amber-600 hover:bg-amber-50 border border-transparent",
          isStreaming && "opacity-40 cursor-not-allowed"
        )}
      >
        <Brain
          className={cn(
            "h-3.5 w-3.5 transition-colors",
            deepThinkingLevel > 0 ? "text-amber-500" : "text-gray-400"
          )}
        />
        <span>{deepThinkingLevel > 0 ? `${deepThinkingLevel}` : "思考"}</span>
        <ChevronDown
          className={cn(
            "h-3 w-3 transition-transform duration-200",
            deepThinkingLevel > 0 ? "text-amber-400" : "text-gray-400",
            open && "rotate-180"
          )}
        />
      </button>

      {open && (
        <div
          ref={panelRef}
          className="absolute bottom-full left-1/2 -translate-x-1/2 z-50 mb-2 w-64 rounded-2xl border border-gray-100 bg-white p-5 shadow-[0_8px_30px_rgba(0,0,0,0.08)]"
        >
          {/* 头部 */}
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <div
                className={cn(
                  "flex h-7 w-7 items-center justify-center rounded-lg",
                  deepThinkingLevel > 0 ? "bg-amber-100" : "bg-gray-100"
                )}
              >
                <Brain
                  className={cn(
                    "h-4 w-4",
                    deepThinkingLevel > 0 ? "text-amber-600" : "text-gray-400"
                  )}
                />
              </div>
              <span className="text-sm font-semibold text-gray-800">深度思考</span>
            </div>
            <span
              className={cn(
                "text-xs font-bold tabular-nums",
                deepThinkingLevel > 0
                  ? "text-amber-600"
                  : "text-gray-400"
              )}
            >
              {deepThinkingLevel === 0 ? "关闭" : `${deepThinkingLevel}%`}
            </span>
          </div>

          {/* 滑块 */}
          <div className="relative h-7 flex items-center">
            <div className="relative w-full h-1.5 rounded-full bg-gray-200">
              {/* 已填充轨道 */}
              <div
                className="absolute left-0 top-0 h-full rounded-full bg-gradient-to-r from-amber-400 to-orange-400 transition-all duration-150"
                style={{ width: `${progress * 100}%` }}
              />
              {/* 滑块把手 */}
              <input
                type="range"
                min={0}
                max={100}
                step={1}
                value={deepThinkingLevel}
                onChange={(e) => setDeepThinkingLevel(Number(e.target.value))}
                className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
              />
              {/* 自定义把手 */}
              <div
                className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2 z-0 transition-all duration-150"
                style={{ left: `${progress * 100}%` }}
              >
                <div
                  className={cn(
                    "h-4 w-4 rounded-full border-2 shadow-sm transition-colors",
                    deepThinkingLevel > 0
                      ? "bg-white border-amber-500 shadow-amber-200"
                      : "bg-white border-gray-300"
                  )}
                />
              </div>
            </div>
          </div>

          {/* 刻度标签 */}
          <div className="flex justify-between mt-1.5 px-0.5">
            {[0, 25, 50, 75, 100].map((v) => (
              <button
                key={v}
                type="button"
                onClick={() => setDeepThinkingLevel(v)}
                className={cn(
                  "text-[10px] font-medium transition-colors px-1 py-0.5 rounded",
                  deepThinkingLevel === v
                    ? "text-amber-600 bg-amber-50"
                    : "text-gray-400 hover:text-gray-600"
                )}
              >
                {v === 0 ? "关" : `${v}`}
              </button>
            ))}
          </div>

          {/* 描述 */}
          <div className="mt-3 pt-3 border-t border-gray-100">
            <p className="text-[11px] text-gray-400 leading-relaxed">
              {deepThinkingLevel === 0
                ? "关闭思考模式，模型直接给出回答"
                : deepThinkingLevel <= 33
                ? "轻度思考模式，适合简单推理任务"
                : deepThinkingLevel <= 66
                ? "中等思考模式，平衡速度与推理深度"
                : "深度思考模式，适合复杂推理与分析任务"}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
