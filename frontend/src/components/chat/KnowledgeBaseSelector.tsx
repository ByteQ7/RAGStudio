import * as React from "react";
import { BookOpen, Check, ChevronDown, Search, X } from "lucide-react";

import { cn } from "@/lib/utils";
import { getKnowledgeBases, type KnowledgeBase } from "@/services/knowledgeService";

interface KnowledgeBaseSelectorProps {
  selectedKnowledgeBaseIds: string[];
  onKnowledgeBaseIdsChange: (ids: string[]) => void;
}

export function KnowledgeBaseSelector({
  selectedKnowledgeBaseIds,
  onKnowledgeBaseIdsChange
}: KnowledgeBaseSelectorProps) {
  const [knowledgeBases, setKnowledgeBases] = React.useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [searchQuery, setSearchQuery] = React.useState("");
  const [isOpen, setIsOpen] = React.useState(false);
  const containerRef = React.useRef<HTMLDivElement | null>(null);
  const searchInputRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    let active = true;
    setLoading(true);
    getKnowledgeBases()
      .then((data) => {
        if (active) {
          setKnowledgeBases(data || []);
        }
      })
      .catch(() => {
        if (active) {
          setKnowledgeBases([]);
        }
      })
      .finally(() => {
        if (active) {
          setLoading(false);
        }
      });
    return () => {
      active = false;
    };
  }, []);

  React.useEffect(() => {
    if (isOpen && searchInputRef.current) {
      searchInputRef.current.focus();
    }
  }, [isOpen]);

  React.useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
        setSearchQuery("");
      }
    };
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  const filtered = React.useMemo(() => {
    if (!searchQuery.trim()) return knowledgeBases;
    const q = searchQuery.trim().toLowerCase();
    return knowledgeBases.filter((kb) => kb.name.toLowerCase().includes(q));
  }, [knowledgeBases, searchQuery]);

  const selectedSet = React.useMemo(
    () => new Set(selectedKnowledgeBaseIds),
    [selectedKnowledgeBaseIds]
  );

  const selectedKBs = React.useMemo(
    () => knowledgeBases.filter((kb) => selectedSet.has(kb.id)),
    [knowledgeBases, selectedSet]
  );

  const toggleKnowledgeBase = (id: string) => {
    const next = selectedSet.has(id)
      ? selectedKnowledgeBaseIds.filter((kbId) => kbId !== id)
      : [...selectedKnowledgeBaseIds, id];
    onKnowledgeBaseIdsChange(next);
  };

  const removeKnowledgeBase = (id: string) => {
    onKnowledgeBaseIdsChange(selectedKnowledgeBaseIds.filter((kbId) => kbId !== id));
  };

  return (
    <div ref={containerRef} className="relative">
      <div className="flex flex-wrap items-center gap-1.5">
        <button
          type="button"
          onClick={() => setIsOpen(!isOpen)}
          className={cn(
            "inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-medium transition-all",
            selectedKnowledgeBaseIds.length > 0
              ? "border-[hsl(var(--primary)/0.3)] bg-[var(--color-fill-quaternary)] text-[hsl(var(--primary))]"
              : "border-transparent bg-[var(--color-fill-secondary)] text-[var(--color-text-secondary)] hover:bg-[var(--color-fill)]"
          )}
        >
          <BookOpen className="h-3.5 w-3.5" />
          知识库
          {selectedKnowledgeBaseIds.length > 0 && (
            <span className="inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-[hsl(var(--primary))] px-1 text-[11px] font-bold text-[hsl(var(--primary-foreground))]">
              {selectedKnowledgeBaseIds.length}
            </span>
          )}
          <ChevronDown className={cn("h-3 w-3 transition-transform", isOpen && "rotate-180")} />
        </button>

        {selectedKBs.map((kb) => (
          <span
            key={kb.id}
            title={kb.description || undefined}
            className="inline-flex items-center gap-1 rounded-full border border-[hsl(var(--primary)/0.3)] bg-[var(--color-fill-quaternary)] px-2.5 py-0.5 text-xs font-medium text-[hsl(var(--primary))]"
          >
            {kb.name}
            <button
              type="button"
              onClick={() => removeKnowledgeBase(kb.id)}
              className="ml-0.5 inline-flex h-3.5 w-3.5 items-center justify-center rounded-full hover:bg-[hsl(var(--primary)/0.15)]"
              aria-label={`移除 ${kb.name}`}
            >
              <X className="h-2.5 w-2.5" />
            </button>
          </span>
        ))}
      </div>

      {isOpen && (
        <div className="absolute bottom-full left-0 z-50 mb-1.5 w-72 rounded-xl border border-[var(--color-border)] bg-[var(--color-bg-elevated)] p-2 shadow-lg">
          <div className="relative mb-1">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[var(--color-text-tertiary)]" />
            <input
              ref={searchInputRef}
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="搜索知识库..."
              className="w-full rounded-lg border border-[var(--color-border-secondary)] bg-[var(--color-bg-container-secondary)] py-1.5 pl-8 pr-3 text-xs text-[var(--color-text)] placeholder:text-[var(--color-text-quaternary)] outline-none focus:border-[hsl(var(--primary)/0.4)] focus:ring-1 focus:ring-[hsl(var(--primary)/0.3)]"
              autoComplete="off"
            />
          </div>

          <div className="max-h-52 overflow-y-auto">
            {loading ? (
              <div className="py-4 text-center text-xs text-[var(--color-text-tertiary)]">
                加载中...
              </div>
            ) : filtered.length === 0 ? (
              <div className="py-4 text-center text-xs text-[var(--color-text-tertiary)]">
                {searchQuery.trim() ? "无匹配结果" : "暂无知识库"}
              </div>
            ) : (
              filtered.map((kb) => {
                const isSelected = selectedSet.has(kb.id);
                return (
                  <button
                    key={kb.id}
                    type="button"
                    onClick={() => toggleKnowledgeBase(kb.id)}
                    className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left text-xs transition-colors hover:bg-[var(--color-fill-quaternary)]"
                  >
                    <span
                      className={cn(
                        "inline-flex h-4 w-4 shrink-0 items-center justify-center rounded border transition-colors",
                        isSelected
                          ? "border-[hsl(var(--primary))] bg-[hsl(var(--primary))] text-[hsl(var(--primary-foreground))]"
                          : "border-[var(--color-border)] bg-[var(--color-bg-container)]"
                      )}
                    >
                      {isSelected && <Check className="h-3 w-3" />}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div
                        className="truncate font-medium text-[var(--color-text)]"
                        title={kb.description || undefined}
                      >
                        {kb.name}
                      </div>
                      {kb.description ? (
                        <div className="truncate text-[var(--color-text-tertiary)]">
                          {kb.description}
                        </div>
                      ) : kb.collectionName ? (
                        <div className="truncate text-[var(--color-text-tertiary)]">
                          {kb.collectionName}
                        </div>
                      ) : null}
                    </div>
                    {kb.documentCount != null && (
                      <span className="shrink-0 text-[var(--color-text-tertiary)]">
                        {kb.documentCount} 文档
                      </span>
                    )}
                  </button>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
}
