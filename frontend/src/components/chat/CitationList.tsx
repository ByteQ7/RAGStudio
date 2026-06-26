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
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
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

  const toggleExpand = (id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  if (!matchedCitations || matchedCitations.length === 0) return null;

  return (
    <div className="mt-4 border-t border-gray-100 pt-3">
      <p className="mb-2 text-xs text-gray-400">
        知识库（{matchedCitations.length}）
      </p>

      <div className="space-y-1.5">
        {matchedCitations.map((citation) => {
          const isExpanded = expandedIds.has(citation.id);
          return (
            <div key={citation.id}>
              <button
                type="button"
                onClick={() => toggleExpand(citation.id)}
                className="flex w-full items-center gap-1.5 rounded-lg border border-gray-100 bg-gray-50/50 px-3 py-1.5 text-left text-xs text-gray-500 hover:bg-gray-100 transition-colors"
              >
                {isExpanded ? (
                  <ChevronDown className="h-3 w-3 flex-shrink-0" />
                ) : (
                  <ChevronRight className="h-3 w-3 flex-shrink-0" />
                )}
                <FileText className="h-3 w-3 flex-shrink-0" />
                <span className="font-mono truncate">{citation.id}</span>
              </button>

              {isExpanded && (
                <div className="mt-1 ml-6 rounded-lg border border-gray-100 bg-white px-3 py-2">
                  <p className="text-xs text-gray-600 leading-relaxed whitespace-pre-wrap">
                    {citation.text}
                  </p>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
