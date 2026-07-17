import * as React from "react";
import {
  Brain,
  CheckCircle2,
  ChevronDown,
  Circle,
  Cog,
  Eye,
  Loader2,
  Sparkles,
  Zap
} from "lucide-react";
import { cn } from "@/lib/utils";
import type { AgentStep } from "@/types";

interface AgentStepsProps {
  steps: AgentStep[];
  thinkingLevel?: number;
}

/* ==========================================================================
   StepDetail — 展开后显示思考、参数、返回结果
   ========================================================================== */

function StepDetail({ step }: { step: AgentStep }) {
  return (
    <div className="mt-1.5 space-y-2 border-l-2 border-indigo-200 pl-3">
      {step.thought ? (
        <div>
          <div className="mb-0.5 flex items-center gap-1 text-[11px] font-medium text-gray-400">
            <Brain className="h-3 w-3" /> 思考
          </div>
          <p className="text-xs leading-relaxed text-gray-600">{step.thought}</p>
        </div>
      ) : null}

      {step.action === "TOOL_CALL" && step.toolInput ? (
        <div>
          <div className="mb-0.5 flex items-center gap-1 text-[11px] font-medium text-gray-400">
            <Cog className="h-3 w-3" /> 参数
          </div>
          <pre className="overflow-x-auto rounded bg-gray-100 px-2 py-1.5 text-[11px] text-gray-600">
            {JSON.stringify(step.toolInput, null, 2)}
          </pre>
        </div>
      ) : null}

      {step.observation ? (
        <div>
          <div className="mb-0.5 flex items-center gap-1 text-[11px] font-medium text-gray-400">
            <Eye className="h-3 w-3" /> 返回结果
          </div>
          <p className="text-xs leading-relaxed text-gray-600 whitespace-pre-wrap">
            {step.observation}
          </p>
        </div>
      ) : step.action === "TOOL_CALL" ? (
        <p className="text-[11px] italic text-gray-400">执行中...</p>
      ) : null}
    </div>
  );
}

/* ==========================================================================
   AgentSteps — 主组件：垂直 Checklist
   ========================================================================== */

export const AgentSteps = React.memo(function AgentSteps({ steps, thinkingLevel = 0 }: AgentStepsProps) {
  if (!steps || steps.length === 0) return null;

  const [expandedSteps, setExpandedSteps] = React.useState<Set<number>>(new Set());

  const toggle = (iteration: number) => {
    setExpandedSteps((prev) => {
      const next = new Set(prev);
      if (next.has(iteration)) {
        next.delete(iteration);
      } else {
        next.add(iteration);
      }
      return next;
    });
  };

  // ── 提取 planSteps（取最后一个有 plan 的 step，支持中途变更） ──
  const planSteps: string[] = (() => {
    for (let i = steps.length - 1; i >= 0; i--) {
      const ps = steps[i].planSteps;
      if (ps && ps.length > 0) return ps;
    }
    return [];
  })();
  const hasPlan = planSteps.length > 0;

  // 检测 plan 是否发生过变更（存在多个不同长度的 plan）
  const planVersions = new Set(
    steps.filter(s => s.planSteps && s.planSteps.length > 0)
         .map(s => s.planSteps!.length)
  );
  const planChanged = planVersions.size > 1;
  const originalPlanCount = planVersions.size > 0 ? Math.min(...planVersions) : 0;

  // ── 计算每条 plan 任务的状态 ──────────────────────────────────
  // 已完成的任务数 = 有 observation 的 step 数
  const completedCount = steps.filter((s) => s.observation).length;
  // 当前执行中的 iteration（第一个 TOOL_CALL 且无 observation）
  const currentStep = steps.find((s) => s.action === "TOOL_CALL" && !s.observation);
  const currentIteration = currentStep?.iteration ?? -1;

  type PlanItemStatus = "completed" | "current" | "pending";
  const planItemStatus = (index: number): PlanItemStatus => {
    if (index < completedCount) return "completed";
    if (index === completedCount && currentIteration >= 0) return "current";
    return "pending";
  };

  // 当前正在执行的任务描述
  const currentPlanItem = hasPlan && currentIteration >= 0 && completedCount < planSteps.length
    ? planSteps[completedCount]
    : null;

  // 当前正在执行的 tool 名称
  const currentToolName = currentStep?.toolName;

  // 确认所有步骤都已完成（最后一个 step 有 observation 或 action 是 FINISH）
  const allDone = steps.length > 0 && steps.every(
    (s) => s.observation || s.action === "FINISH" || s.action === "ERROR"
  );

  return (
    <div className="mb-3 overflow-hidden rounded-xl border border-indigo-100 bg-white shadow-sm">
      {/* ── Header ───────────────────────────────────────────── */}
      <div className="flex items-center gap-1.5 border-b border-indigo-50 px-4 py-2.5">
        <Brain className="h-4 w-4 text-indigo-500" />
        <span className="text-xs font-semibold text-indigo-700">推理过程</span>
        {thinkingLevel > 0 && (
          <span className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-amber-100 to-orange-100 px-2 py-0.5 text-[10px] font-semibold text-amber-700 border border-amber-200/60">
            <Sparkles className="h-2.5 w-2.5" />
            深度思考 {thinkingLevel}%
          </span>
        )}
        <span className="ml-auto rounded-full bg-indigo-50 px-2 py-0.5 text-[11px] font-medium text-indigo-500">
          {hasPlan ? planSteps.length : steps.length} 步
        </span>
        {allDone && (
          <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
        )}
      </div>

      {/* ── Plan Checklist ──────────────────────────────────── */}
      {hasPlan && (
        <div className="px-4 py-3">
          {/* Plan 变更提示 */}
          {planChanged && (
            <div className="mb-2 flex items-center gap-1.5 rounded-lg bg-amber-50 border border-amber-200 px-3 py-1.5">
              <Sparkles className="h-3.5 w-3.5 shrink-0 text-amber-500" />
              <span className="text-[12px] font-medium text-amber-700">
                计划已更新（{originalPlanCount}→{planSteps.length}步）
              </span>
            </div>
          )}
          <div className="space-y-1">
            {planSteps.map((task, idx) => {
              const status = planItemStatus(idx);
              const step = steps.find((s) => s.iteration === idx);
              const isExpanded = step != null && expandedSteps.has(idx);

              return (
                <div key={idx}>
                  <button
                    type="button"
                    onClick={() => step && toggle(idx)}
                    disabled={!step}
                    className={cn(
                      "flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm transition-colors",
                      status === "completed" && "text-gray-500",
                      status === "current" && "bg-indigo-50/60 text-indigo-800 font-medium",
                      status === "pending" && "text-gray-400",
                      step && "hover:bg-gray-50 cursor-pointer",
                      !step && "cursor-default"
                    )}
                  >
                    {/* Status icon */}
                    {status === "completed" && (
                      <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-500" />
                    )}
                    {status === "current" && (
                      <Loader2 className="h-4 w-4 shrink-0 animate-spin text-indigo-500" />
                    )}
                    {status === "pending" && (
                      <Circle className="h-4 w-4 shrink-0 text-gray-300" />
                    )}

                    {/* Step number badge */}
                    <span className={cn(
                      "inline-flex h-5 min-w-[2rem] items-center justify-center rounded text-[11px] font-medium",
                      status === "completed" && "bg-emerald-50 text-emerald-600",
                      status === "current" && "bg-indigo-100 text-indigo-700",
                      status === "pending" && "bg-gray-100 text-gray-400"
                    )}>
                      第{idx + 1}步
                    </span>

                    {/* Task text */}
                    <span className="flex-1 leading-snug">{task}</span>

                    {/* Expand chevron */}
                    {step && (
                      <ChevronDown
                        className={cn(
                          "h-3.5 w-3.5 shrink-0 text-gray-400 transition-transform duration-200",
                          isExpanded && "rotate-180"
                        )}
                      />
                    )}
                  </button>

                  {/* Expanded detail */}
                  {step && isExpanded && (
                    <div className="px-3 pb-2">
                      <StepDetail step={step} />
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* ── Status bar — 没有 plan 时显示传统步骤列表 ─────────── */}
      {!hasPlan && (
        <div className="divide-y divide-gray-50">
          {steps.map((step) => {
            const isExpanded = expandedSteps.has(step.iteration);
            return (
              <div key={step.iteration}>
                <button
                  type="button"
                  onClick={() => toggle(step.iteration)}
                  className="flex w-full items-center gap-2 px-4 py-2.5 text-left text-xs transition-colors hover:bg-gray-100/50"
                >
                  {step.action === "TOOL_CALL" && !step.observation ? (
                    <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-amber-500" />
                  ) : step.observation ? (
                    <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-500" />
                  ) : (
                    <Zap className="h-3.5 w-3.5 shrink-0 text-emerald-500" />
                  )}
                  <span className="font-medium text-gray-600">
                    第{step.iteration + 1}步 · {step.action === "TOOL_CALL" ? "调用工具" : step.action === "FINISH" ? "完成推理" : "异常"}
                  </span>
                  {step.toolName && (
                    <code className="rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-500">
                      {step.toolName}
                    </code>
                  )}
                  {step.durationMs > 0 && (
                    <span className="ml-auto text-[11px] text-gray-400">{step.durationMs}ms</span>
                  )}
                  <ChevronDown
                    className={cn(
                      "h-3 w-3 shrink-0 text-gray-400 transition-transform duration-200",
                      isExpanded && "rotate-180"
                    )}
                  />
                </button>
                {isExpanded && <div className="px-4 pb-2"><StepDetail step={step} /></div>}
              </div>
            );
          })}
        </div>
      )}

      {/* ── Current step status ──────────────────────────────── */}
      {currentPlanItem && currentToolName && !allDone && (
        <div className="border-t border-indigo-50 bg-indigo-50/30 px-4 py-2">
          <p className="text-[12px] text-indigo-600">
            正在 <code className="rounded bg-indigo-100 px-1 py-0.5 font-mono text-[11px]">{currentToolName}</code> — {currentPlanItem}
          </p>
        </div>
      )}
    </div>
  );
});
