import * as React from "react";
import { Brain, ChevronDown, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

interface ThinkingPanelProps {
  /** 思考过程内容 */
  thinking?: string;
  /** 思考耗时（秒），有值时展示徽标 */
  durationSeconds?: number;
  /** 消息是否仍在流式输出中（true 时默认展开并显示思考中动画） */
  streaming?: boolean;
}

/**
 * 思考过程折叠面板
 *
 * 独立于正文的思考内容展示区（SSE type=think 通道累积 / 历史消息 thinkingContent）：
 * - 流式中默认展开，标题栏显示脉冲"思考中"，内容区自动滚动跟随
 * - 完成后/历史消息默认折叠，点击展开查看完整思维链
 */
export const ThinkingPanel = React.memo(function ThinkingPanel({
  thinking,
  durationSeconds,
  streaming = false
}: ThinkingPanelProps) {
  const [manualExpanded, setManualExpanded] = React.useState<boolean | null>(null);
  const contentRef = React.useRef<HTMLDivElement | null>(null);
  const prevLenRef = React.useRef(0);

  // 展开状态：流式输出中默认展开；用户手动切换后以手动状态为准
  const expanded = manualExpanded ?? (streaming && !!thinking);

  // 流式且展开时，内容增长自动滚动到底部（用户主动上滚则不打断）
  React.useEffect(() => {
    if (!expanded || !streaming || !contentRef.current) return;
    const el = contentRef.current;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    if (nearBottom && thinking && thinking.length !== prevLenRef.current) {
      el.scrollTop = el.scrollHeight;
    }
    prevLenRef.current = thinking?.length ?? 0;
  }, [thinking, expanded, streaming]);

  if (!thinking || thinking.trim().length === 0) return null;

  return (
    <div className="mb-3 overflow-hidden rounded-xl border border-amber-100 bg-amber-50/40">
      <button
        type="button"
        onClick={() => setManualExpanded(!expanded)}
        className="flex w-full items-center gap-1.5 px-4 py-2 text-left text-xs transition-colors hover:bg-amber-50"
      >
        {streaming ? (
          <Loader2 className="h-4 w-4 shrink-0 animate-spin text-amber-500" />
        ) : (
          <Brain className="h-4 w-4 shrink-0 text-amber-500" />
        )}
        <span className={cn(
          "font-semibold",
          streaming ? "text-amber-600" : "text-amber-700"
        )}>
          {streaming ? "思考中…" : "思考过程"}
        </span>
        {durationSeconds != null && durationSeconds > 0 && !streaming && (
          <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-medium text-amber-600">
            {durationSeconds}s
          </span>
        )}
        <ChevronDown
          className={cn(
            "ml-auto h-3.5 w-3.5 shrink-0 text-amber-400 transition-transform duration-200",
            expanded && "rotate-180"
          )}
        />
      </button>
      {expanded && (
        <div
          ref={contentRef}
          className="max-h-64 overflow-y-auto whitespace-pre-wrap break-words border-t border-amber-100 px-4 py-2.5 text-[13px] leading-relaxed text-gray-500"
        >
          {thinking}
        </div>
      )}
    </div>
  );
});
