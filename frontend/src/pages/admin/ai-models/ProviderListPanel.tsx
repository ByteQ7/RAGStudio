import { useMemo } from "react";
import { Cpu, Loader2, Plug, PlugZap } from "lucide-react";

import { cn } from "@/lib/utils";
import type { AiProvider } from "@/services/aiModelConfigService";

interface ProviderListPanelProps {
  providers: AiProvider[];
  loading: boolean;
  selectedId: string | null;
  onSelect: (id: string) => void;
}

export function ProviderListPanel({
  providers,
  loading,
  selectedId,
  onSelect
}: ProviderListPanelProps) {
  const { enabled, disabled } = useMemo(() => {
    const enabled: AiProvider[] = [];
    const disabled: AiProvider[] = [];
    for (const p of providers) {
      if (p.enabled === 1) {
        enabled.push(p);
      } else {
        disabled.push(p);
      }
    }
    return { enabled, disabled };
  }, [providers]);

  const renderProvider = (provider: AiProvider) => {
    const isSelected = selectedId === provider.id;
    const isActive = provider.enabled === 1;

    return (
      <button
        key={provider.id}
        type="button"
        onClick={() => onSelect(provider.id)}
        className={cn(
          "group relative flex w-full items-center gap-2 rounded-lg border px-3 py-2 text-left transition-all duration-150",
          isSelected
            ? "border-indigo-200 bg-white shadow-sm"
            : "border-transparent bg-white hover:border-gray-200 hover:shadow-sm"
        )}
      >
        {/* 指示条 */}
        <span
          className={cn(
            "absolute left-0 top-1.5 bottom-1.5 w-[2.5px] rounded-full transition-all duration-150",
            isSelected
              ? "bg-indigo-500"
              : "bg-transparent group-hover:bg-gray-200"
          )}
        />

        {/* 图标 */}
        {provider.iconUrl ? (
          <img
            src={provider.iconUrl}
            alt={provider.name}
            className={cn(
              "h-6 w-6 shrink-0 rounded-lg object-contain",
              !isActive && "opacity-40"
            )}
          />
        ) : (
          <div
            className={cn(
              "flex h-6 w-6 shrink-0 items-center justify-center rounded-lg text-[10px] font-semibold transition-colors",
              isActive
                ? "bg-indigo-50 text-indigo-600"
                : "bg-gray-50 text-gray-400"
            )}
          >
            {provider.name.charAt(0).toUpperCase()}
          </div>
        )}

        {/* 信息 */}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1">
            <span
              className={cn(
                "truncate text-xs font-medium leading-tight",
                isActive ? "text-gray-900" : "text-gray-400"
              )}
            >
              {provider.displayName || provider.name}
            </span>
            {isActive ? (
              <span className="inline-block h-1 w-1 shrink-0 rounded-full bg-emerald-400" />
            ) : (
              <span className="inline-block h-1 w-1 shrink-0 rounded-full bg-gray-200" />
            )}
          </div>
          <span
            className={cn(
              "block truncate text-[10px] mt-px",
              isActive ? "text-gray-400" : "text-gray-300"
            )}
          >
            {provider.name}
          </span>
        </div>

        {/* 模型计数 */}
        <span
          className={cn(
            "shrink-0 rounded-md px-1.5 py-0.5 text-[10px] font-medium tabular-nums",
            isActive
              ? "bg-indigo-50 text-indigo-500"
              : "bg-gray-50 text-gray-400"
          )}
        >
          {provider.modelCount ?? 0}
        </span>
      </button>
    );
  };

  if (loading && providers.length === 0) {
    return (
      <div className="flex h-full items-center justify-center text-gray-400">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
        <span className="text-xs">加载中...</span>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col gap-4">
      {/* 已启用 */}
      <section>
        <div className="mb-2 flex items-center gap-1.5 px-0.5">
          <span className="text-[10px] font-semibold uppercase tracking-wider text-emerald-600">已启用</span>
          <span className="text-[10px] text-gray-300 ml-auto">{enabled.length}</span>
        </div>
        <div className="space-y-1">
          {enabled.length > 0 ? (
            enabled.map(renderProvider)
          ) : (
            <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-gray-200 py-6 text-gray-400">
              <PlugZap className="h-4 w-4" strokeWidth={1.5} />
              <p className="text-[11px]">暂无启用的供应商</p>
            </div>
          )}
        </div>
      </section>

      {/* 未启用 */}
      <section>
        <div className="mb-2 flex items-center gap-1.5 px-0.5">
          <span className="text-[10px] font-semibold uppercase tracking-wider text-gray-400">未启用</span>
          <span className="text-[10px] text-gray-300 ml-auto">{disabled.length}</span>
        </div>
        <div className="space-y-1">
          {disabled.length > 0 ? (
            disabled.map(renderProvider)
          ) : (
            <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-gray-200 py-6 text-gray-400">
              <Cpu className="h-4 w-4" strokeWidth={1.5} />
              <p className="text-[11px]">暂无未启用的供应商</p>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
