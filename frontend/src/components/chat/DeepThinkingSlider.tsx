import { useRef, useState, useEffect, useCallback, useMemo } from "react";
import { Brain, ChevronDown, Sparkles } from "lucide-react";

import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

/**
 * 一串沿着进度条浮动的小粒子（CSS 动画实现）
 */
function ParticleDots({ count, progress }: { count: number; progress: number }) {
  const particles = useMemo(() => {
    return Array.from({ length: count }, (_, i) => {
      const pos = (i + 1) / (count + 1) * progress;
      const delay = Math.random() * 2;
      const duration = 1.5 + Math.random() * 1.5;
      const size = 2 + Math.random() * 2;
      const drift = (Math.random() - 0.5) * 6;
      return { pos, delay, duration, size, drift };
    });
  }, [count, progress]);

  if (progress <= 0.02) return null;

  return (
    <div className="absolute inset-0 pointer-events-none overflow-hidden" style={{ borderRadius: "inherit" }}>
      {particles.map((p, i) => (
        <span
          key={i}
          className="absolute top-1/2 -translate-y-1/2 rounded-full bg-amber-300/60 animate-pulse-soft"
          style={{
            left: `calc(${p.pos * 100}% - ${p.size / 2}px)`,
            width: p.size,
            height: p.size,
            animationDelay: `${p.delay}s`,
            animationDuration: `${p.duration}s`,
            transform: `translateY(calc(-50% + ${p.drift}px))`,
          }}
        />
      ))}
    </div>
  );
}

/**
 * 深度思考滑块 — 紧凑版
 */
export function DeepThinkingSlider() {
  const deepThinkingLevel = useChatStore((s) => s.deepThinkingLevel);
  const setDeepThinkingLevel = useChatStore((s) => s.setDeepThinkingLevel);
  const isStreaming = useChatStore((s) => s.isStreaming);
  const [open, setOpen] = useState(false);
  const [dragging, setDragging] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const btnRef = useRef<HTMLButtonElement>(null);

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

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      setDeepThinkingLevel(Number(e.target.value));
    },
    [setDeepThinkingLevel]
  );

  const descText =
    deepThinkingLevel === 0
      ? "关闭思考，模型直接回答"
      : deepThinkingLevel <= 33
        ? "轻度思考，适合简单推理"
        : deepThinkingLevel <= 66
          ? "中等思考，平衡速度与推理"
          : "深度思考，适合复杂分析与代码";

  return (
    <div className="relative">
      <button
        ref={btnRef}
        type="button"
        onClick={() => !isStreaming && setOpen(!open)}
        disabled={isStreaming}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium transition-all duration-200 select-none",
          deepThinkingLevel > 0
            ? "bg-gradient-to-r from-amber-50 to-orange-50 text-amber-700 border border-amber-200/60 shadow-sm"
            : "text-gray-400 hover:text-amber-600 hover:bg-amber-50 border border-transparent",
          isStreaming && "opacity-40 cursor-not-allowed"
        )}
      >
        <Brain className={cn("h-3.5 w-3.5", deepThinkingLevel > 0 && "text-amber-500")} />
        <span>{deepThinkingLevel > 0 ? `${deepThinkingLevel}%` : "思考"}</span>
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
          className="absolute bottom-full left-1/2 -translate-x-1/2 z-50 mb-2 w-[260px] rounded-xl border border-gray-100 bg-white p-4 shadow-[0_8px_28px_rgba(0,0,0,0.10)]"
        >
          {/* 头部 — 一行搞定 */}
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <Brain
                className={cn(
                  "h-4 w-4",
                  deepThinkingLevel > 0 ? "text-amber-500" : "text-gray-400"
                )}
              />
              <span className="text-sm font-semibold text-gray-800">深度思考</span>
            </div>
            <span
              className={cn(
                "text-xs font-bold tabular-nums leading-none",
                deepThinkingLevel > 0 ? "text-amber-600" : "text-gray-400"
              )}
            >
              {deepThinkingLevel === 0 ? "关闭" : `${deepThinkingLevel}%`}
            </span>
          </div>

          {/* 滑块轨道 */}
          <div className="relative h-8 flex items-center">
            {/* 未填充轨道 */}
            <div className="relative w-full h-1.5 rounded-full bg-gray-100 overflow-hidden">
              {/* 已填充 + 粒子 */}
              <div
                className="absolute left-0 top-0 h-full rounded-full"
                style={{
                  width: `${progress * 100}%`,
                  background: `linear-gradient(90deg, #fbbf24 ${Math.min(progress * 100, 30)}%, #f97316)`,
                  boxShadow: progress > 0 ? "0 0 6px rgba(251,146,60,0.3)" : "none",
                }}
              >
                <ParticleDots count={8} progress={progress} />
              </div>
            </div>

            {/* 原生 range */}
            <input
              type="range"
              min={0}
              max={100}
              step={1}
              value={deepThinkingLevel}
              onChange={handleChange}
              onMouseDown={() => setDragging(true)}
              onMouseUp={() => setDragging(false)}
              onTouchStart={() => setDragging(true)}
              onTouchEnd={() => setDragging(false)}
              className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-30 [touch-action:none]"
              style={{ padding: "0 1px" }}
              aria-label="深度思考强度"
            />

            {/* 滑块把手 */}
            <div
              className="absolute top-1/2 -translate-y-1/2 z-10 pointer-events-none transition-transform duration-75"
              style={{ left: `calc(${progress * 100}% - 10px)` }}
            >
              <div
                className={cn(
                  "flex items-center justify-center rounded-full border-2 bg-white transition-all duration-75",
                  deepThinkingLevel > 0
                    ? dragging
                      ? "h-6 w-6 border-amber-500 shadow-[0_0_10px_rgba(251,146,60,0.4)] scale-110"
                      : "h-5 w-5 border-amber-500 shadow-[0_1px_4px_rgba(251,146,60,0.25)]"
                    : dragging
                      ? "h-6 w-6 border-amber-400 shadow-[0_0_8px_rgba(251,146,60,0.25)] scale-110"
                      : "h-5 w-5 border-gray-300"
                )}
              >
                {deepThinkingLevel > 0 && (
                  <Sparkles className="h-2.5 w-2.5 text-amber-500" strokeWidth={2.5} />
                )}
              </div>
            </div>
          </div>

          {/* 刻度标签 */}
          <div className="flex justify-between mt-1.5 px-0.5">
            {[
              { v: 0, l: "关" },
              { v: 25, l: "25" },
              { v: 50, l: "50" },
              { v: 75, l: "75" },
              { v: 100, l: "100" },
            ].map(({ v, l }) => (
              <button
                key={v}
                type="button"
                onClick={() => setDeepThinkingLevel(v)}
                className={cn(
                  "text-[11px] font-medium transition-all rounded select-none px-1.5 py-0.5",
                  deepThinkingLevel === v
                    ? "text-amber-600 bg-amber-50"
                    : "text-gray-400 hover:text-gray-600"
                )}
              >
                {l}
              </button>
            ))}
          </div>

          {/* 描述条 */}
          <div className="mt-2.5 pt-2.5 border-t border-gray-100 flex items-center gap-1.5">
            <Sparkles
              className={cn(
                "h-3 w-3 shrink-0",
                deepThinkingLevel > 0 ? "text-amber-400" : "text-gray-300"
              )}
            />
            <p className="text-[11px] text-gray-400 leading-tight">{descText}</p>
          </div>
        </div>
      )}
    </div>
  );
}
