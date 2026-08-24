import { ENTITY_TYPES, typeColor } from "./graphVisual";

interface GraphLegendProps {
  hiddenTypes: Set<string>;
  onToggleType: (type: string) => void;
}

export function GraphLegend({ hiddenTypes, onToggleType }: GraphLegendProps) {
  return (
    <div
      className="absolute bottom-3 left-3 z-10 flex flex-col gap-1 rounded-xl border bg-white/90 p-2.5 shadow-sm backdrop-blur dark:bg-[#161b22]/90"
      style={{ borderColor: "var(--color-border-secondary)" }}
    >
      <p className="mb-0.5 text-[10px] font-medium" style={{ color: "var(--color-text-tertiary)" }}>实体类型（点击过滤）</p>
      <div className="grid grid-cols-3 gap-x-3 gap-y-1">
        {ENTITY_TYPES.map((type) => {
          const hidden = hiddenTypes.has(type);
          return (
            <button
              key={type}
              type="button"
              onClick={() => onToggleType(type)}
              className="flex items-center gap-1.5 text-[10px] transition-opacity hover:opacity-80"
              style={{ color: "var(--color-text-secondary)", opacity: hidden ? 0.35 : 1 }}
            >
              <span
                className="h-2 w-2 rounded-full"
                style={{ background: typeColor(type), opacity: hidden ? 0.3 : 1 }}
              />
              {type}
              {hidden && <span className="line-through" />}
            </button>
          );
        })}
      </div>
    </div>
  );
}