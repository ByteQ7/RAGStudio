import type { DragEvent } from "react";
import {
  Boxes,
  FileInput,
  FileSearch,
  GitBranch,
  Layers,
  Sparkles,
  Wand2
} from "lucide-react";

import { cn } from "@/lib/utils";

import { NODE_TYPE_OPTIONS, type PipelineNodeType } from "../pipeline/pipelineFormModel";

/** 拖拽时写入 dataTransfer 的 MIME 类型 */
export const PIPELINE_NODE_DND_TYPE = "application/ragstudio-pipeline-node";

const TYPE_ICONS: Record<string, React.ReactNode> = {
  fetcher: <FileInput className="h-4 w-4 text-sky-600" />,
  parser: <FileSearch className="h-4 w-4 text-violet-600" />,
  chunker: <Layers className="h-4 w-4 text-indigo-500" />,
  enhancer: <Sparkles className="h-4 w-4 text-pink-500" />,
  enricher: <Wand2 className="h-4 w-4 text-teal-600" />,
  indexer: <Boxes className="h-4 w-4 text-orange-500" />,
  graph_extractor: <GitBranch className="h-4 w-4 text-lime-600" />
};

/** 左侧节点面板：拖拽处理节点/条件网关到画布 */
export function IngestionNodePalette() {
  const handleDragStart = (event: DragEvent<HTMLDivElement>, payload: string) => {
    event.dataTransfer.setData(PIPELINE_NODE_DND_TYPE, payload);
    event.dataTransfer.effectAllowed = "move";
  };

  return (
    <aside className="flex w-48 shrink-0 flex-col gap-2 overflow-y-auto border-r border-[var(--color-border-secondary)] bg-[var(--color-bg-container)] p-3">
      <p className="px-1 text-xs font-medium text-[var(--color-text-tertiary)]">拖拽添加节点</p>
      {NODE_TYPE_OPTIONS.map((option) => (
        <div
          key={option.value}
          draggable
          onDragStart={(e) => handleDragStart(e, `processor:${option.value}`)}
          title={option.description}
          className={cn(
            "flex cursor-grab items-center gap-2.5 rounded-lg border px-2.5 py-2 transition-colors",
            "border-[var(--color-border)] hover:border-indigo-300 hover:bg-indigo-50/50 active:cursor-grabbing",
            "dark:hover:border-indigo-800 dark:hover:bg-indigo-950/30"
          )}
        >
          {TYPE_ICONS[option.value] ?? <Boxes className="h-4 w-4" />}
          <div className="min-w-0">
            <p className="text-sm font-medium text-[var(--color-text)]">{option.short}</p>
            <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">{option.description}</p>
          </div>
        </div>
      ))}

      <div className="my-1 border-t border-dashed border-[var(--color-border-secondary)]" />
      <div
        draggable
        onDragStart={(e) => handleDragStart(e, "condition")}
        title="排他分支：出边配置条件，最多一条无条件兜底"
        className={cn(
          "flex cursor-grab items-center gap-2.5 rounded-lg border px-2.5 py-2 transition-colors",
          "border-amber-200 hover:bg-amber-50/60 active:cursor-grabbing",
          "dark:border-amber-900/70 dark:hover:bg-amber-950/30"
        )}
      >
        <GitBranch className="h-4 w-4 text-amber-600" />
        <div className="min-w-0">
          <p className="text-sm font-medium text-[var(--color-text)]">条件分支</p>
          <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">排他分支 + 兜底</p>
        </div>
      </div>
    </aside>
  );
}

export type { PipelineNodeType };
