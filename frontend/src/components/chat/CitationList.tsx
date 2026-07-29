import { useEffect, useMemo, useState } from "react";
import { Check, ChevronDown, ChevronRight, Copy, Image, Loader2, X } from "lucide-react";
import type { Message, Citation } from "@/types";
import { getPresignedUrl } from "@/utils/image";
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
  const [imageUrls, setImageUrls] = useState<Record<string, string>>({});
  const [loadingImages, setLoadingImages] = useState<Set<string>>(new Set());
  const [expandedImage, setExpandedImage] = useState<string | null>(null);
  const citations = message.citations;
  const answer = message.content;

  useEffect(() => {
    if (!Array.isArray(citations)) return;
    for (const c of citations) {
      if (c.contentType === "IMAGE" && c.imageUrl && !imageUrls[c.id]) {
        setLoadingImages((prev) => new Set(prev).add(c.id));
        getPresignedUrl(c.imageUrl)
          .then((url) => {
            setImageUrls((prev) => ({ ...prev, [c.id]: url }));
            setLoadingImages((prev) => {
              const next = new Set(prev);
              next.delete(c.id);
              return next;
            });
          })
          .catch(() => {
            setLoadingImages((prev) => {
              const next = new Set(prev);
              next.delete(c.id);
              return next;
            });
          });
      }
    }
  }, [citations]);

  // 匹配答案中被引用的 chunk ID，按出现顺序编号
  const { matchedCitations, idToNum } = useMemo(() => {
    if (!Array.isArray(citations) || citations.length === 0 || !answer) {
      return { matchedCitations: [] as Citation[], idToNum: {} as Record<string, number> };
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
      // 按在答案中出现的先后顺序排列，而非 citations 数组的原始顺序
      const citationMap = new Map(citations.map((c) => [c.id, c]));
      const matched = markedIds
        .map((id) => citationMap.get(id))
        .filter((c): c is NonNullable<typeof c> => c !== undefined);
      // 按出现顺序编号
      const numMap: Record<string, number> = {};
      markedIds.forEach((id, idx) => {
        if (citationMap.has(id)) {
          numMap[id] = idx + 1;
        }
      });
      return { matchedCitations: matched, idToNum: numMap };
    }

    // 方案B：连续 10 字匹配（兜底），IMAGE 类型无需匹配直接保留
    const MIN_MATCH_LEN = 10;
    const matched = citations.filter((chunk) => {
      if (chunk.contentType === "IMAGE") return true;
      if (!chunk.text) return false;
      const text = chunk.text;
      for (let i = 0; i <= text.length - MIN_MATCH_LEN; i++) {
        const snippet = text.substring(i, i + MIN_MATCH_LEN);
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
      if (!chunkId || !matchedCitations?.some((c) => c.id === chunkId)) return;

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
  };

  if (!matchedCitations || matchedCitations.length === 0) return null;

  return (
    <div className="mt-4 pt-3" style={{ borderTop: '1px solid var(--color-border-secondary)' }}>
      <p className="mb-2 text-xs" style={{ color: 'var(--color-text-tertiary)' }}>
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
                  className="flex flex-1 items-center gap-1.5 rounded-l-lg border border-r-0 px-3 py-1.5 text-left text-xs transition-colors"
                  style={{ borderColor: 'var(--color-border-secondary)', background: 'var(--color-fill-quaternary)', color: 'var(--color-text-secondary)' }}
                >
                  {isExpanded ? (
                    <ChevronDown className="h-3 w-3 flex-shrink-0" />
                  ) : (
                    <ChevronRight className="h-3 w-3 flex-shrink-0" />
                  )}
                  <span className="font-mono font-medium min-w-[1.5em]" style={{ color: 'hsl(var(--primary))' }}>
                    [{num}]
                  </span>
                  <span className="font-mono truncate" style={{ color: 'var(--color-text-tertiary)' }}>{citation.chunkId || citation.id}</span>
                  {citation.contentType === "IMAGE" && (
                    <Image className="h-3 w-3 flex-shrink-0" style={{ color: 'var(--color-text-tertiary)' }} />
                  )}
                  {citation.kbName || citation.docName ? (
                    <span className="hidden sm:inline truncate text-xs ml-1" style={{ color: 'var(--color-text-tertiary)' }}>
                      {citation.kbName || ''}{citation.kbName && citation.docName ? ' · ' : ''}{citation.docName || ''}
                    </span>
                  ) : null}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    const text = citation.chunkId || citation.id;
                    handleCopyId(text);
                  }}
                  className={`flex-shrink-0 rounded-r-lg border px-2 py-1.5 transition-all duration-200 ${copiedId === (citation.chunkId || citation.id) ? "scale-110" : ""}`}
                  style={{
                    borderColor: copiedId === (citation.chunkId || citation.id) ? 'hsl(var(--success))' : 'var(--color-border-secondary)',
                    background: copiedId === (citation.chunkId || citation.id) ? 'rgba(34,197,94,0.12)' : 'var(--color-fill-quaternary)',
                    color: copiedId === (citation.chunkId || citation.id) ? 'hsl(var(--success))' : 'var(--color-text-tertiary)'
                  }}
                  title={copiedId === (citation.chunkId || citation.id) ? "已复制" : "复制 Chunk ID"}
                >
                  {copiedId === (citation.chunkId || citation.id) ? (
                    <Check className="h-3.5 w-3.5" />
                  ) : (
                    <Copy className="h-3.5 w-3.5" />
                  )}
                </button>
              </div>

              {isExpanded && (
                <div className="mt-1 ml-6 rounded-lg border px-3 py-2" style={{ borderColor: 'var(--color-border-secondary)', background: 'var(--color-bg-container-secondary)' }}>
                  {citation.contentType === "IMAGE" ? (
                    <div className="space-y-2">
                      {loadingImages.has(citation.id) ? (
                        <div className="flex items-center gap-2 text-xs" style={{ color: 'var(--color-text-tertiary)' }}>
                          <Loader2 className="h-3 w-3 animate-spin" />
                          加载图片中...
                        </div>
                      ) : imageUrls[citation.id] ? (
                        <button
                          type="button"
                          onClick={() => setExpandedImage(imageUrls[citation.id])}
                          className="block w-full text-left cursor-zoom-in"
                        >
                          <img
                            src={imageUrls[citation.id]}
                            alt={`知识库引用图片 [${num}]`}
                            className="max-w-full max-h-64 rounded object-contain"
                            loading="lazy"
                          />
                        </button>
                      ) : (
                        <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>
                          无法加载图片
                        </p>
                      )}
                      {citation.text && (
                        <p className="text-xs leading-relaxed whitespace-pre-wrap" style={{ color: 'var(--color-text-secondary)' }}>
                          {citation.text}
                        </p>
                      )}
                    </div>
                  ) : (
                    <p className="text-xs leading-relaxed whitespace-pre-wrap" style={{ color: 'var(--color-text-secondary)' }}>
                      {citation.text}
                    </p>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {expandedImage && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm"
          onClick={() => setExpandedImage(null)}
          onKeyDown={(e) => e.key === "Escape" && setExpandedImage(null)}
          role="dialog"
          aria-modal="true"
          aria-label="图片预览"
        >
          <button
            type="button"
            onClick={() => setExpandedImage(null)}
            className="absolute right-4 top-4 flex h-9 w-9 items-center justify-center rounded-full text-white transition-colors"
            style={{ background: 'rgba(255,255,255,0.15)' }}
          >
            <X className="h-5 w-5" />
          </button>
          <img
            src={expandedImage}
            alt="预览大图"
            className="max-h-[90vh] max-w-[90vw] rounded-xl object-contain shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </div>
  );
}

