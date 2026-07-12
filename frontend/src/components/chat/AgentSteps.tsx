import * as React from "react";
import { Brain, ChevronDown, Cog, Eye, ListOrdered, Sparkles, Zap } from "lucide-react";
import { cn } from "@/lib/utils";
import type { AgentStep } from "@/types";

interface AgentStepsProps {
  steps: AgentStep[];
  thinkingLevel?: number;
}

const actionIcons: Record<string, React.ReactNode> = {
  TOOL_CALL: <Cog className="h-3.5 w-3.5 text-amber-500" />,
  FINISH: <Zap className="h-3.5 w-3.5 text-emerald-500" />,
  ERROR: <Zap className="h-3.5 w-3.5 text-rose-500" />
};

const actionLabels: Record<string, string> = {
  TOOL_CALL: "调用工具",
  FINISH: "完成推理",
  ERROR: "异常"
};

function StepHeader({
  step,
  expanded,
  onToggle
}: {
  step: AgentStep;
  expanded: boolean;
  onToggle: () => void;
}) {
  const icon = actionIcons[step.action] ?? <Brain className="h-3.5 w-3.5 text-gray-400" />;
  const label = actionLabels[step.action] ?? step.action;

  return (
    <button
      type="button"
      onClick={onToggle}
      className="flex w-full items-center gap-2 px-3 py-2 text-left text-xs transition-colors hover:bg-gray-100/50"
    >
      {icon}
      <span className="font-medium text-gray-600">
        第{step.iteration + 1}步 · {label}
      </span>
      {step.toolName ? (
        <code className="rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-500">
          {step.toolName}
        </code>
      ) : null}
      {step.durationMs > 0 ? (
        <span className="ml-auto text-[11px] text-gray-400">{step.durationMs}ms</span>
      ) : null}
      <ChevronDown
        className={cn(
          "h-3 w-3 text-gray-400 transition-transform duration-200",
          expanded && "rotate-180"
        )}
      />
    </button>
  );
}

function StepDetail({ step }: { step: AgentStep }) {
  return (
    <div className="space-y-2 px-3 pb-3">
      {step.thought ? (
        <div>
          <div className="mb-1 flex items-center gap-1 text-[11px] font-medium text-gray-400">
            <Brain className="h-3 w-3" /> 思考
          </div>
          <p className="text-xs leading-relaxed text-gray-600">{step.thought}</p>
        </div>
      ) : null}

      {step.action === "TOOL_CALL" && step.toolInput ? (
        <div>
          <div className="mb-1 flex items-center gap-1 text-[11px] font-medium text-gray-400">
            <Cog className="h-3 w-3" /> 参数
          </div>
          <pre className="overflow-x-auto rounded bg-gray-100 px-2 py-1.5 text-[11px] text-gray-600">
            {JSON.stringify(step.toolInput, null, 2)}
          </pre>
        </div>
      ) : null}

      {step.observation ? (
        <div>
          <div className="mb-1 flex items-center gap-1 text-[11px] font-medium text-gray-400">
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

  // 提取 Plan（取自第一个携带 plan 的 step）
  const plan = steps.find((s) => s.plan)?.plan;

  return (
    <div className="mb-3 overflow-hidden rounded-lg border border-indigo-100 bg-white">
      <div className="flex items-center gap-1.5 border-b border-indigo-50 px-3 py-2">
        <Brain className="h-3.5 w-3.5 text-indigo-500" />
        <span className="text-xs font-medium text-indigo-700">推理过程</span>
        {thinkingLevel > 0 && (
          <span className="inline-flex items-center gap-1 rounded-full bg-gradient-to-r from-amber-100 to-orange-100 px-2 py-0.5 text-[10px] font-semibold text-amber-700 border border-amber-200/60">
            <Sparkles className="h-2.5 w-2.5" />
            深度思考 {deepThinkingLevel}%
          </span>
        )}
        <span className="ml-auto rounded-full bg-indigo-50 px-2 py-0.5 text-[11px] text-indigo-500">
          {steps.length} 步
        </span>
      </div>
      {plan ? (
        <div className="border-b border-indigo-50 bg-indigo-50/30 px-3 py-2">
          <div className="flex items-start gap-1.5">
            <ListOrdered className="mt-0.5 h-3.5 w-3.5 shrink-0 text-indigo-500" />
            <p className="text-[12px] leading-relaxed text-indigo-700 whitespace-pre-wrap">{plan}</p>
          </div>
        </div>
      ) : null}
      <div className="divide-y divide-gray-50">
        {steps.map((step) => (
          <div key={step.iteration}>
            <StepHeader
              step={step}
              expanded={expandedSteps.has(step.iteration)}
              onToggle={() => toggle(step.iteration)}
            />
            {expandedSteps.has(step.iteration) ? <StepDetail step={step} /> : null}
          </div>
        ))}
      </div>
    </div>
  );
});
