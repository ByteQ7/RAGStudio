import { Search } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import type { GraphEntity } from "@/services/graphService";
import { getGraphEntities } from "@/services/graphService";
import { getErrorMessage } from "@/utils/error";

interface GraphSearchBoxProps {
  kbId: string;
  onLocate: (entityId: string) => void;
}

export function GraphSearchBox({ kbId, onLocate }: GraphSearchBoxProps) {
  const [keyword, setKeyword] = useState("");
  const [options, setOptions] = useState<GraphEntity[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const blurTimeoutRef = useRef<number | null>(null);

  useEffect(() => {
    const kw = keyword.trim();
    if (kw.length < 2) {
      setOptions([]);
      setOpen(false);
      return;
    }
    setLoading(true);
    const handle = window.setTimeout(() => {
      getGraphEntities(kbId, { keyword: kw, current: 1, size: 10 })
        .then((result) => {
          setOptions(result.records);
          setOpen(true);
        })
        .catch((err) => toast.error(getErrorMessage(err, "搜索实体失败")))
        .finally(() => setLoading(false));
    }, 300);
    return () => window.clearTimeout(handle);
  }, [keyword, kbId]);

  const handleSelect = (entity: GraphEntity) => {
    setOpen(false);
    setKeyword("");
    onLocate(entity.id);
  };

  return (
    <div className="absolute left-3 top-3 z-10 w-[240px]">
      <div className="relative">
        <Search
          className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2"
          style={{ color: "var(--color-text-tertiary)" }}
        />
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onFocus={() => { if (options.length > 0) setOpen(true); }}
          onBlur={() => { blurTimeoutRef.current = window.setTimeout(() => setOpen(false), 150); }}
          placeholder="搜索实体并定位..."
          className="h-8 w-full rounded-lg border bg-white/90 pl-9 pr-3 text-xs shadow-sm backdrop-blur focus:outline-none dark:bg-[#161b22]/90"
          style={{ borderColor: "var(--color-border-secondary)", color: "var(--color-text)" }}
        />
      </div>
      {open && (
        <div
          className="absolute left-0 right-0 top-full z-50 mt-1 max-h-[260px] overflow-y-auto rounded-xl border py-1 shadow-lg"
          style={{ borderColor: "var(--color-border)", background: "var(--color-bg-elevated)" }}
          onMouseDown={(e) => e.preventDefault()}
        >
          {loading && <div className="px-3 py-2 text-xs" style={{ color: "var(--color-text-tertiary)" }}>搜索中...</div>}
          {!loading && options.length === 0 && (
            <div className="px-3 py-2 text-xs" style={{ color: "var(--color-text-tertiary)" }}>无匹配实体</div>
          )}
          {options.map((entity) => (
            <button
              key={entity.id}
              type="button"
              onClick={() => handleSelect(entity)}
              className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs transition-colors hover:bg-[var(--color-fill-quaternary)]"
            >
              <span className="truncate font-medium" style={{ color: "var(--color-text)" }}>{entity.displayName}</span>
              <span className="shrink-0 text-[10px]" style={{ color: "var(--color-text-tertiary)" }}>{entity.entityType}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}