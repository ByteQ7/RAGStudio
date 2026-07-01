import { useEffect, useMemo, useState } from "react";
import { Check, ChevronDown, ChevronRight, Copy } from "lucide-react";
import type { Message } from "@/types";

interface CitationListProps {
  message: Message;
}

/**
 * 引用溯源组件
 * <p>
 * 解析答案中的 [^chunk_{id}] 标记，渲染为编号的知识库引用列表。
 * 支持从外部通过 expand-citation 事件展开指定条目。
 * </p>
 */
export function CitationList({ message }: CitationListProps) {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const citations = message.citations;
  const answer = message.content;

  // 匹配答案中被引用的 chunk ID，按出现顺序编号
  const { matchedCitations, idToNum } = useMemo(() => {
    if (!citations || citations.length === 0 || !answer) {
      return { matchedCitations: [] as typeof citations, idToNum: {} as Record<string, number> };
    }

    const markerRegex = /\[\^chunk_(\w+)\]/g;
    const markedIds: string[] = [];
    let match;
    while ((match = markerRegex.exec(answer)) !== null) {
      const id = match[1];
      if (!markedIds.includes(id)) {
        markedIds.push(id);
      }
    }

    if (markedIds.length > 0) {
      const matched = citations.filter((c) => markedIds.includes(c.id));
      // 按出现顺序编号
      const numMap: Record<string, number> = {};
      markedIds.forEach((id, idx) => {
        if (matched.some((c) => c.id === id)) {
          numMap[id] = idx + 1;
        }
      });
      return { matchedCitations: matched, idToNum: numMap };
    }

    // 方案B：连续 10 字匹配（兜底）
    const MIN_MATCH_LEN = 10;
    const matched = citations.filter((chunk) => {
      if (!chunk.text) return false;
      for (let i = 0; i <= chunk.text.length - MIN_MATCH_LEN; i++) {
        const snippet = chunk.text.substring(i, i + MIN_MATCH_LEN);
        if (answer.includes(snippet)) return true;
      }
      return false;
    });
    const numMap: Record<string, number> = {};
    matched.forEach((c, idx) => { numMap[c.id] = idx + 1; });
    return { matchedCitations: matched, idToNum: numMap };
  }, [citations, answer]);

  // 监听 inline 引用点击事件，展开 + 滚动到对应条目
  useEffect(() => {
    const handler = (e: Event) => {
      const chunkId = (e as CustomEvent).detail as string;
      if (!chunkId || !matchedCitations.some((c) => c.id === chunkId)) return;

      setExpandedIds((prev) => {
        if (prev.has(chunkId)) return prev;
        const next = new Set(prev);
        next.add(chunkId);
        return next;
      });

      setTimeout(() => {
        const el = document.getElementById(`citation-${chunkId}`);
        el?.scrollIntoView({ behavior: "smooth", block: "nearest" });
      }, 50);
    };
    window.addEventListener("expand-citation", handler);
    return () => window.removeEventListener("expand-citation", handler);
  }, [matchedCitations]);

  const handleCopyId = async (id: string) => {
    try {
      await navigator.clipboard.writeText(id);
      setCopiedId(id);
      setTimeout(() => setCopiedId(null), 1500);
    } catch {
      // ignore
    }
  };

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
    // 同步派发事件，让正文高亮与展开/折叠联动
    window.dispatchEvent(new CustomEvent('expand-citation', { detail: id }));
  };

  if (!matchedCitations || matchedCitations.length === 0) return null;

  return (
    <div className="mt-4 border-t border-gray-100 pt-3">
      <p className="mb-2 text-xs text-gray-400">
        知识库引用（{matchedCitations.length}）
      </p>

      <div className="space-y-1.5">
        {matchedCitations.map((citation) => {
          const num = idToNum[citation.id] || "?";
          const isExpanded = expandedIds.has(citation.id);
          return (
            <div key={citation.id} id={`citation-${citation.id}`}>
              <div className="flex items-stretch gap-0">
                <button
                  type="button"
                  onClick={() => toggleExpand(citation.id)}
                  className="flex flex-1 items-center gap-1.5 rounded-l-lg border border-r-0 border-gray-100 bg-gray-50/50 px-3 py-1.5 text-left text-xs text-gray-500 hover:bg-gray-100 transition-colors"
                >
                  {isExpanded ? (
                    <ChevronDown className="h-3 w-3 flex-shrink-0" />
                  ) : (
                    <ChevronRight className="h-3 w-3 flex-shrink-0" />
                  )}
                  <span className="font-mono text-[#0969da] dark:text-[#58a6ff] font-medium min-w-[1.5em]">
                    [{num}]
                  </span>
                  <span className="font-mono truncate text-gray-400">{citation.id}</span>
                  {citation.kbName || citation.docName ? (
                    <span className="hidden sm:inline truncate text-gray-400 text-xs ml-1">
                      {citation.kbName || ''}{citation.kbName && citation.docName ? ' · ' : ''}{citation.docName || ''}
                    </span>
                  ) : null}
                </button>
                <button
                  type="button"
                  onClick={() => handleCopyId(citation.id)}
                  className="flex-shrink-0 rounded-r-lg border border-gray-100 bg-gray-50/50 px-2 py-1.5 text-gray-400 hover:text-[#0969da] dark:hover:text-[#58a6ff] hover:bg-gray-100 transition-colors"
                  title="复制 Chunk ID"
                >
                  {copiedId === citation.id ? (
                    <Check className="h-3.5 w-3.5 text-green-500" />
                  ) : (
                    <Copy className="h-3.5 w-3.5" />
                  )}
                </button>
              </div>

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