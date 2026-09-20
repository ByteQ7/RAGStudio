import { Handle, Position, type NodeProps } from "@xyflow/react";
import {
  Boxes,
  FileInput,
  FileOutput,
  FileSearch,
  GitBranch,
  Layers,
  Play,
  Sparkles,
  Wand2
} from "lucide-react";

import { cn } from "@/lib/utils";

import { getNodeTypeShortLabel, summarizeNode } from "../pipeline/pipelineFormModel";
import type { IngestionFlowNode } from "./pipelineGraphModel";

const HANDLE_STYLE = "!h-2.5 !w-2.5 !border-2 !border-white dark:!border-[var(--color-bg-container)]";

const NODE_ICONS: Record<string, React.ReactNode> = {
  fetcher: <FileInput className="h-3.5 w-3.5" />,
  parser: <FileSearch className="h-3.5 w-3.5" />,
  chunker: <Layers className="h-3.5 w-3.5" />,
  enhancer: <Sparkles className="h-3.5 w-3.5" />,
  enricher: <Wand2 className="h-3.5 w-3.5" />,
  indexer: <Boxes className="h-3.5 w-3.5" />,
  graph_extractor: <GitBranch className="h-3.5 w-3.5" />
};

/** 开始节点 */
export function PipelineStartNode({ selected }: NodeProps) {
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
      <Handle type="source" position={Position.Right} className={cn(HANDLE_STYLE, "!bg-emerald-500")} />
    </div>
  );
}

/** 结束节点 */
export function PipelineEndNode({ selected }: NodeProps) {
  return (
    <div
      className={cn(
        "flex h-11 items-center gap-2 rounded-full border bg-[var(--color-bg-container)] px-4 shadow-sm",
        "border-[var(--color-border)]",
        selected && "ring-2 ring-slate-400 ring-offset-1 dark:ring-offset-transparent"
      )}
    >
      <Handle type="target" position={Position.Left} className={cn(HANDLE_STYLE, "!bg-slate-400")} />
      <FileOutput className="h-3.5 w-3.5 text-[var(--color-text-tertiary)]" />
      <span className="text-sm font-medium text-[var(--color-text)]">结束</span>
    </div>
  );
}

/** 条件网关节点（分支条件挂在出边上） */
export function PipelineConditionNode({ data, selected }: NodeProps<IngestionFlowNode>) {
  return (
    <div
      className={cn(
        "w-[200px] rounded-xl border bg-[var(--color-bg-container)] px-3 py-2.5 shadow-sm transition-shadow",
        selected
          ? "border-amber-400 ring-2 ring-amber-100 dark:ring-amber-950"
          : "border-amber-200 hover:shadow-md dark:border-amber-900/70"
      )}
    >
      <Handle type="target" position={Position.Left} className={cn(HANDLE_STYLE, "!bg-amber-500")} />
      <div className="flex items-start gap-2">
        <GitBranch className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-medium text-amber-600 dark:text-amber-400">条件分支</p>
          <p className="mt-0.5 line-clamp-2 text-xs leading-snug text-[var(--color-text-secondary)]">
            {(data.note as string) || "每条出边配置条件，最多一条无条件兜底"}
          </p>
        </div>
      </div>
      <Handle type="source" position={Position.Right} className={cn(HANDLE_STYLE, "!bg-amber-500")} />
    </div>
  );
}

/** 处理器节点（fetcher/parser/chunker/...） */
export function PipelineProcessorNode({ data, selected }: NodeProps<IngestionFlowNode>) {
  const form = data.form;
  if (!form) {
    return null;
  }
  const subtitle = summarizeNode(form.nodeType, form);
  const hasGate = Boolean(form.condition.trim());
  return (
    <div
      className={cn(
        "w-[240px] rounded-xl border bg-[var(--color-bg-container)] px-3 py-2.5 shadow-sm transition-shadow",
        selected
          ? "border-indigo-400 ring-2 ring-indigo-100 dark:ring-indigo-950"
          : "border-[var(--color-border)] hover:shadow-md"
      )}
    >
      <Handle type="target" position={Position.Left} className={cn(HANDLE_STYLE, "!bg-indigo-400")} />
      <div className="flex items-start gap-2">
        <span className="mt-0.5 text-indigo-500 dark:text-indigo-400">
          {NODE_ICONS[form.nodeType] ?? <Boxes className="h-3.5 w-3.5" />}
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-[var(--color-text)]">
            {getNodeTypeShortLabel(form.nodeType)}
          </p>
          <p className="mt-0.5 line-clamp-1 font-mono text-[11px] text-[var(--color-text-tertiary)]">
            {form.nodeId}
          </p>
        </div>
      </div>
      {subtitle ? (
        <p className="mt-1.5 line-clamp-1 text-[11px] text-[var(--color-text-secondary)]">{subtitle}</p>
      ) : null}
      {hasGate ? (
        <span className="mt-1.5 inline-flex max-w-full items-center truncate rounded bg-amber-100 px-1.5 py-0.5 text-[11px] text-amber-700 dark:bg-amber-950/60 dark:text-amber-300">
          节点条件
        </span>
      ) : null}
      <Handle type="source" position={Position.Right} className={cn(HANDLE_STYLE, "!bg-indigo-400")} />
    </div>
  );
}

/** 画布节点类型注册表（模块级常量，避免重渲染告警） */
export const PIPELINE_NODE_TYPES = {
  start: PipelineStartNode,
  processor: PipelineProcessorNode,
  condition: PipelineConditionNode,
  end: PipelineEndNode
};
