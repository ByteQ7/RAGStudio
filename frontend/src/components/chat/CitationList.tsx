import { useMemo, useState } from "react";
import { ChevronDown, ChevronRight, FileText } from "lucide-react";
import type { Message } from "@/types";

interface CitationListProps {
  message: Message;
}

/**
 * 引用溯源组件
 * <p>
 * 方案A：解析答案中的 [^chunk_{id}] 标记，匹配 chunks 中对应的内容
 * 方案B：方案A未命中时，用答案文本与 chunks 做连续 10 字匹配
 * </p>
 */
export function CitationList({ message }: CitationListProps) {
  const [open, setOpen] = useState(false);
  const citations = message.citations;
  const answer = message.content;

  const matchedCitations = useMemo(() => {
    if (!citations || citations.length === 0 || !answer) return [];

    // 方案A：扫描 [^chunk_{id}] 标记
    const markerRegex = /\[\^chunk_(\w+)\]/g;
    const markedIds = new Set<string>();
    let match;
    while ((match = markerRegex.exec(answer)) !== null) {
      markedIds.add("chunk_" + match[1]);
    }

    if (markedIds.size > 0) {
      return citations.filter((c) => markedIds.has(c.id));
    }

    // 方案B：连续 10 字匹配
    const MIN_MATCH_LEN = 10;
    return citations.filter((chunk) => {
      if (!chunk.text) return false;
      for (let i = 0; i <= chunk.text.length - MIN_MATCH_LEN; i++) {
        const snippet = chunk.text.substring(i, i + MIN_MATCH_LEN);
        if (answer.includes(snippet)) return true;
      }
      return false;
    });
  }, [citations, answer]);

  if (!matchedCitations || matchedCitations.length === 0) return null;

  return (
    <div className="mt-4 border-t border-gray-100 pt-3">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1.5 text-xs text-gray-400 hover:text-gray-600 transition-colors"
      >
        {open ? (
          <ChevronDown className="h-3.5 w-3.5" />
        ) : (
          <ChevronRight className="h-3.5 w-3.5" />
        )}
        参考文献（{matchedCitations.length}）
      </button>

      {open && (
        <div className="mt-2 space-y-2">
          {matchedCitations.map((citation) => (
            <div
              key={citation.id}
              className="rounded-lg border border-gray-100 bg-gray-50/50 px-3 py-2"
            >
              <div className="flex items-center gap-1.5 text-xs text-gray-400 mb-1">
                <FileText className="h-3 w-3" />
                <span className="font-mono">{citation.id}</span>
                {citation.score > 0 && (
                  <span className="ml-auto">
                    相关性 {(citation.score * 100).toFixed(0)}%
                  </span>
                )}
              </div>
              <p className="text-xs text-gray-600 leading-relaxed line-clamp-3">
                {citation.text}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
