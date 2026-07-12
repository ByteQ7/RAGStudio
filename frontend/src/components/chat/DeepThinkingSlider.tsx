import { useState } from "react";
import { Brain, ChevronDown, ChevronUp } from "lucide-react";

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
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => !isStreaming && setExpanded(!expanded)}
        disabled={isStreaming}
        className={cn(
          "flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-xs transition-colors",
          deepThinkingLevel > 0
            ? "text-amber-600 bg-amber-50 hover:bg-amber-100"
            : "text-gray-400 hover:text-amber-500 hover:bg-amber-50",
          isStreaming && "opacity-40 cursor-not-allowed"
        )}
        title={deepThinkingLevel > 0 ? `深度思考 ${deepThinkingLevel}%` : "关闭思考"}
      >
        <Brain className={cn("h-4 w-4", deepThinkingLevel > 0 && "text-amber-500")} />
        <span className="hidden sm:inline">
          {deepThinkingLevel > 0 ? `${deepThinkingLevel}%` : "思考"}
        </span>
        {expanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
      </button>

      {expanded && (
        <div
          className="absolute bottom-full left-0 z-50 mb-2 w-56 rounded-xl border border-gray-200 bg-white p-4 shadow-lg"
          onMouseDown={(e) => e.preventDefault()}
        >
          <div className="flex items-center justify-between mb-2">
            <span className="text-xs font-medium text-gray-700">
              {deepThinkingLevel === 0 ? "关闭思考" : `深度思考 ${deepThinkingLevel}%`}
            </span>
          </div>
          <input
            type="range"
            min={0}
            max={100}
            step={1}
            value={deepThinkingLevel}
            onChange={(e) => setDeepThinkingLevel(Number(e.target.value))}
            className="w-full h-1.5 rounded-full appearance-none cursor-pointer
              accent-amber-500 bg-gray-200
              [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:h-4 [&::-webkit-slider-thumb]:w-4
              [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-amber-500
              [&::-webkit-slider-thumb]:shadow-sm [&::-webkit-slider-thumb]:cursor-pointer"
          />
          <div className="flex justify-between text-[10px] text-gray-400 mt-1">
            <span>关闭</span>
            <span>最大</span>
          </div>
        </div>
      )}
    </div>
  );
}
