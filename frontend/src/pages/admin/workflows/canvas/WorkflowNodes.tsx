import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Circle, Flag, GitBranch, Play } from "lucide-react";

import { cn } from "@/lib/utils";

import type { FlowNode } from "./graphModel";

const HANDLE_STYLE = "!h-2.5 !w-2.5 !border-2 !border-white dark:!border-[var(--color-bg-container)]";

/** 开始节点 */
export function StartNode({ selected }: NodeProps) {
  return (
    <div
      className={cn(
        "flex h-11 items-center gap-2 rounded-full border bg-[var(--color-bg-container)] px-4 shadow-sm",
        "border-emerald-300 dark:border-emerald-800",
        selected && "ring-2 ring-emerald-400 ring-offset-1 dark:ring-offset-transparent"
      )}
    >
      <Play className="h-3.5 w-3.5 text-emerald-600 dark:text-emerald-400" />
      <span className="text-sm font-medium text-[var(--color-text)]">开始</span>
      <Handle
        type="source"
        position={Position.Right}
        className={cn(HANDLE_STYLE, "!bg-emerald-500")}
      />
    </div>
  );
}

/** 结束节点 */
export function EndNode({ selected }: NodeProps) {
  return (
    <div
      className={cn(
        "flex h-11 items-center gap-2 rounded-full border bg-[var(--color-bg-container)] px-4 shadow-sm",
        "border-[var(--color-border)]",
        selected && "ring-2 ring-slate-400 ring-offset-1 dark:ring-offset-transparent"
      )}
    >
      <Handle
        type="target"
        position={Position.Left}
        className={cn(HANDLE_STYLE, "!bg-slate-400")}
      />
      <Flag className="h-3.5 w-3.5 text-[var(--color-text-tertiary)]" />
      <span className="text-sm font-medium text-[var(--color-text)]">结束</span>
    </div>
  );
}

/** 步骤节点 */
export function StepNode({ data, selected }: NodeProps<FlowNode>) {
  return (
    <div
      className={cn(
        "w-60 rounded-xl border bg-[var(--color-bg-container)] px-3 py-2.5 shadow-sm transition-shadow",
        selected
          ? "border-indigo-400 ring-2 ring-indigo-100 dark:ring-indigo-950"
          : "border-[var(--color-border)] hover:shadow-md"
      )}
    >
      <Handle
        type="target"
        position={Position.Left}
        className={cn(HANDLE_STYLE, "!bg-indigo-400")}
      />
      <div className="flex items-start gap-2">
        <Circle className="mt-1 h-3 w-3 shrink-0 fill-indigo-400 text-indigo-400" />
        <p className="line-clamp-3 min-w-0 flex-1 text-sm leading-snug text-[var(--color-text)]">
          {data.action || "（未填写动作）"}
        </p>
      </div>
      <div className="mt-2 flex flex-wrap gap-1">
        {data.tool ? (
          <span className="inline-flex max-w-full items-center truncate rounded bg-[var(--color-fill-tertiary)] px-1.5 py-0.5 font-mono text-[11px] text-[var(--color-text-secondary)]">
            {data.tool}
          </span>
        ) : null}
        {data.when ? (
          <span className="inline-flex max-w-full items-center truncate rounded bg-amber-100 px-1.5 py-0.5 text-[11px] text-amber-700 dark:bg-amber-950/60 dark:text-amber-300">
            条件：{data.when}
          </span>
        ) : null}
      </div>
      <Handle
        type="source"
        position={Position.Right}
        className={cn(HANDLE_STYLE, "!bg-indigo-400")}
      />
    </div>
  );
}

/** 条件分支节点 */
export function ConditionNode({ data, selected }: NodeProps<FlowNode>) {
  return (
    <div
      className={cn(
        "w-[220px] rounded-xl border bg-[var(--color-bg-container)] px-3 py-2.5 shadow-sm transition-shadow",
        selected
          ? "border-amber-400 ring-2 ring-amber-100 dark:ring-amber-950"
          : "border-amber-200 hover:shadow-md dark:border-amber-900/70"
      )}
    >
      <Handle
        type="target"
        position={Position.Left}
        className={cn(HANDLE_STYLE, "!bg-amber-500")}
      />
      <div className="flex items-start gap-2">
        <GitBranch className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-medium text-amber-600 dark:text-amber-400">条件分支</p>
          <p className="mt-0.5 line-clamp-3 text-sm leading-snug text-[var(--color-text)]">
            {data.expression || "（未填写条件）"}
          </p>
        </div>
      </div>
      <p className="mt-1.5 text-right text-[11px] text-[var(--color-text-tertiary)]">
        每条出边需填写分支标签
      </p>
      <Handle
        type="source"
        position={Position.Right}
        className={cn(HANDLE_STYLE, "!bg-amber-500")}
      />
    </div>
  );
}

/** 画布节点类型注册表（必须定义在组件外，避免每次渲染重建导致重渲染告警） */
export const FLOW_NODE_TYPES = {
  start: StartNode,
  step: StepNode,
  condition: ConditionNode,
  end: EndNode
};
