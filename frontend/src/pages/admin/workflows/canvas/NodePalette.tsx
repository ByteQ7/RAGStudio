import type { DragEvent } from "react";
import { Circle, Flag, GitBranch, Play } from "lucide-react";

import { cn } from "@/lib/utils";

import type { WorkflowNodeKind } from "@/services/workflowService";

import { NODE_KIND_LABELS } from "./graphModel";

/** 拖拽时写入 dataTransfer 的 MIME 类型 */
export const NODE_DND_TYPE = "application/ragstudio-workflow-node";

interface PaletteItem {
  kind: WorkflowNodeKind;
  icon: React.ReactNode;
  description: string;
}

const ITEMS: PaletteItem[] = [
  { kind: "start", icon: <Play className="h-4 w-4 text-emerald-600" />, description: "流程入口（唯一）" },
  { kind: "step", icon: <Circle className="h-4 w-4 fill-indigo-400 text-indigo-400" />, description: "一个执行步骤" },
  { kind: "condition", icon: <GitBranch className="h-4 w-4 text-amber-600" />, description: "按条件走不同分支" },
  { kind: "end", icon: <Flag className="h-4 w-4 text-[var(--color-text-tertiary)]" />, description: "流程出口（唯一）" }
];

/**
 * 左侧节点面板：拖拽到画布添加节点。
 * 开始/结束节点各自仅允许一个，已有时置灰（由父组件通过 disabledKinds 控制）。
 */
export function NodePalette({ disabledKinds }: { disabledKinds: Set<WorkflowNodeKind> }) {
  const handleDragStart = (event: DragEvent<HTMLDivElement>, kind: WorkflowNodeKind) => {
    event.dataTransfer.setData(NODE_DND_TYPE, kind);
    event.dataTransfer.effectAllowed = "move";
  };

  return (
    <aside className="flex w-44 shrink-0 flex-col gap-2 border-r border-[var(--color-border-secondary)] bg-[var(--color-bg-container)] p-3">
      <p className="px-1 text-xs font-medium text-[var(--color-text-tertiary)]">拖拽添加节点</p>
      {ITEMS.map((item) => {
        const disabled = disabledKinds.has(item.kind);
        return (
          <div
            key={item.kind}
            draggable={!disabled}
            onDragStart={(e) => handleDragStart(e, item.kind)}
            title={disabled ? `${NODE_KIND_LABELS[item.kind]}节点已存在` : "拖拽到画布添加"}
            className={cn(
              "flex items-center gap-2.5 rounded-lg border px-2.5 py-2 transition-colors",
              disabled
                ? "cursor-not-allowed border-dashed border-[var(--color-border-secondary)] opacity-50"
                : "cursor-grab border-[var(--color-border)] bg-[var(--color-bg-container)] hover:border-indigo-300 hover:bg-indigo-50/50 active:cursor-grabbing dark:hover:border-indigo-800 dark:hover:bg-indigo-950/30"
            )}
          >
            {item.icon}
            <div className="min-w-0">
              <p className="text-sm font-medium text-[var(--color-text)]">
                {NODE_KIND_LABELS[item.kind]}
              </p>
              <p className="truncate text-[11px] text-[var(--color-text-tertiary)]">
                {item.description}
              </p>
            </div>
          </div>
        );
      })}
    </aside>
  );
}
