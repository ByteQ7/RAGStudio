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
          <div className="mb-0.5 flex items-center gap-1 text-[12px] font-medium text-gray-400">
            <Brain className="h-3 w-3" /> 思考
          </div>
          <p className="text-xs leading-relaxed text-gray-600">{step.thought}</p>
        </div>
      ) : null}

      {step.action === "TOOL_CALL" && step.toolInput ? (
        <div>
          <div className="mb-0.5 flex items-center gap-1 text-[12px] font-medium text-gray-400">
            <Cog className="h-3 w-3" /> 参数
          </div>
          <pre className="overflow-x-auto rounded bg-gray-100 px-2 py-1.5 text-[12px] text-gray-600">
            {JSON.stringify(step.toolInput, null, 2)}
          </pre>
        </div>
      ) : null}

      {step.observation ? (
        <div>
          <div className="mb-0.5 flex items-center gap-1 text-[12px] font-medium text-gray-400">
            <Eye className="h-3 w-3" /> 返回结果
          </div>
          <p className="text-xs leading-relaxed text-gray-600 whitespace-pre-wrap">
            {step.observation}
          </p>
        </div>
      ) : step.action === "TOOL_CALL" ? (
        <p className="text-[12px] italic text-gray-400">执行中...</p>
      ) : null}
    </div>
  );
}

/* ==========================================================================
   AgentSteps — 主组件
   ========================================================================== */

export const AgentSteps = React.memo(function AgentSteps({ steps, thinkingLevel = 0 }: AgentStepsProps) {
  // execution accordion 的折叠状态（默认全部折叠）
  const [expandedSteps, setExpandedSteps] = React.useState<Set<number>>(new Set());

  if (!steps || steps.length === 0) return null;

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

  // ── 提取 planSteps ──
  const planSteps: string[] = (() => {
    for (let i = steps.length - 1; i >= 0; i--) {
      const ps = steps[i].planSteps;
      if (ps && ps.length > 0) return ps;
    }
    return [];
  })();
  const hasPlan = planSteps.length > 0;

  // 检测 plan 是否发生过变更
  const planVersions = new Set(
    steps.filter(s => s.planSteps && s.planSteps.length > 0)
         .map(s => s.planSteps!.length)
  );
  const planChanged = planVersions.size > 1;
  const originalPlanCount = planVersions.size > 0 ? Math.min(...planVersions) : 0;

  // 已完成的任务数
  const completedCount = steps.filter((s) => s.observation || s.action === "FINISH" || s.action === "ERROR").length;
  const currentStep = steps.find((s) => s.action === "TOOL_CALL" && !s.observation);
  const currentIteration = currentStep?.iteration ?? -1;

  type PlanItemStatus = "completed" | "current" | "pending";
  const planItemStatus = (index: number): PlanItemStatus => {
    if (index < completedCount) return "completed";
    if (index === completedCount && currentIteration >= 0) return "current";
    return "pending";
  };

  const currentPlanItem = hasPlan && currentIteration >= 0 && completedCount < planSteps.length
    ? planSteps[completedCount]
    : null;
  const currentToolName = currentStep?.toolName;

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
          <span className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-amber-100 to-orange-100 px-2 py-0.5 text-[11px] font-semibold text-amber-700 border border-amber-200/60">
            <Sparkles className="h-2.5 w-2.5" />
            深度思考 {thinkingLevel}%
          </span>
        )}
        <span className="ml-auto rounded-full bg-indigo-50 px-2 py-0.5 text-[12px] font-medium text-indigo-500">
          {hasPlan ? planSteps.length : steps.length} 步
        </span>
        {allDone && (
          <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />
        )}
      </div>

      {/* ── Plan（默认展开） ─────────────────────────────────── */}
      {hasPlan && (
        <div className="px-4 py-3">
          {planChanged && (
            <div className="mb-2 flex items-center gap-1.5 rounded-lg bg-amber-50 border border-amber-200 px-3 py-1.5">
              <Sparkles className="h-3.5 w-3.5 shrink-0 text-amber-500" />
              <span className="text-[13px] font-medium text-amber-700">
                计划已更新（{originalPlanCount}→{planSteps.length}步）
              </span>
            </div>
          )}
          <div className="space-y-2">
            {planSteps.map((task, idx) => {
              const status = planItemStatus(idx);
              return (
                <div
                  key={idx}
                  className={cn(
                    "flex items-center gap-2.5 rounded-lg px-3 py-2",
                    status === "completed" && "text-gray-500",
                    status === "current" && "bg-indigo-50/60 text-indigo-800 font-medium",
                    status === "pending" && "text-gray-400"
                  )}
                >
                  {status === "completed" && (
                    <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-500" />
                  )}
                  {status === "current" && (
                    <Loader2 className="h-4 w-4 shrink-0 animate-spin text-indigo-500" />
                  )}
                  {status === "pending" && (
                    <Circle className="h-4 w-4 shrink-0 text-gray-300" />
                  )}
                  <span className={cn(
                    "inline-flex h-5 min-w-[2rem] items-center justify-center rounded text-[12px] font-medium",
                    status === "completed" && "bg-emerald-50 text-emerald-600",
                    status === "current" && "bg-indigo-100 text-indigo-700",
                    status === "pending" && "bg-gray-100 text-gray-400"
                  )}>
                    第{idx + 1}步
                  </span>
                  <span className="text-sm leading-snug">{task}</span>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* ── 执行过程（Thought/Action/Observation，默认折叠） ──── */}
      {(hasPlan ? (
        <div className="border-t border-indigo-50">
          <div className="divide-y divide-gray-50">
            {steps.filter(s => s.observation || s.action === "TOOL_CALL").map((step) => {
              const isExpanded = expandedSteps.has(step.iteration);
              return (
                <div key={step.iteration}>
                  <button
                    type="button"
                    onClick={() => toggle(step.iteration)}
                    className="flex w-full items-center gap-2 px-4 py-1.5 text-left text-xs transition-colors hover:bg-gray-100/50"
                  >
                    {step.observation ? (
                      <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-500" />
                    ) : (
                      <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin text-amber-500" />
                    )}
                    <span className="font-medium text-gray-500">
                      第{step.iteration + 1}步 ·
                      {step.toolName ? ` ${step.toolName}` : " 执行"}
                    </span>
                    {step.durationMs > 0 && (
                      <span className="ml-auto text-[12px] text-gray-400">{step.durationMs}ms</span>
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
        </div>
      ) : (
        /* ── 无 Plan 时的传统步骤列表 ────────────────────────── */
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
                    <code className="rounded bg-gray-100 px-1.5 py-0.5 text-[12px] text-gray-500">
                      {step.toolName}
                    </code>
                  )}
                  {step.durationMs > 0 && (
                    <span className="ml-auto text-[12px] text-gray-400">{step.durationMs}ms</span>
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
      ))}

      {/* ── Current step status ──────────────────────────────── */}
      {currentPlanItem && currentToolName && !allDone && (
        <div className="border-t border-indigo-50 bg-indigo-50/30 px-4 py-2">
          <p className="text-[13px] text-indigo-600">
            正在 <code className="rounded bg-indigo-100 px-1 py-0.5 font-mono text-[12px]">{currentToolName}</code> — {currentPlanItem}
          </p>
        </div>
      )}
    </div>
  );
});
