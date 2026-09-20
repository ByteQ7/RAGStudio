import * as React from "react";
import {
  AlertTriangle,
  ArrowUp,
  Check,
  CheckCircle2,
  Pencil,
  Workflow as WorkflowIcon,
  XCircle,
} from "lucide-react";

import { useChatStore } from "@/stores/chatStore";
import { cn } from "@/lib/utils";

export interface WorkflowConfirmStep {
  action: string;
  tool?: string | null;
  when?: string | null;
}

export interface WorkflowConfirmData {
  draftId: string;
  name: string;
  title: string;
  description: string;
  notes?: string | null;
  steps: WorkflowConfirmStep[];
  /** 与已有工作流同名：确认保存将覆盖更新 */
  overwrite?: boolean;
}

/** 用户决策（回执态）：由后续用户消息推断，刷新后仍可还原 */
export type WorkflowDecision = "confirmed" | "cancelled" | "edited" | null;

interface WorkflowConfirmProps {
  data: WorkflowConfirmData;
  /** 是否为最后一条消息：历史消息中的卡片只读（草稿可能已过期，交互无意义） */
  isLast?: boolean;
  /** 从后续用户消息推断的决策（回执态） */
  decision?: WorkflowDecision;
}

/**
 * 工作流确认卡片
 *
 * Agent 提取工作流后，最终回答中携带 [WORKFLOW_CONFIRM] JSON 载荷，
 * 由 MessageItem 解析后渲染本组件。三段式状态机：
 * <ul>
 *   <li><b>待确认</b>（request）：确认保存 / 取消 / 内联输入修改意见</li>
 *   <li><b>提交中</b>：已发送消息，等待下一轮回答（短暂过渡态）</li>
 *   <li><b>回执</b>（receipt）：只读展示决策结果，role="status" 供读屏播报</li>
 * </ul>
 * 视觉权重分层：主操作实心、取消为文字按钮（降低误触）；
 * 修改意见输入框常驻可见，无需二次点击展开。
 */
export function WorkflowConfirm({ data, isLast = true, decision = null }: WorkflowConfirmProps) {
  const [localDecision, setLocalDecision] = React.useState<WorkflowDecision>(null);
  const [supplement, setSupplement] = React.useState("");
  const [showSteps, setShowSteps] = React.useState(true);
  const textareaRef = React.useRef<HTMLTextAreaElement>(null);
  const sendMessage = useChatStore((s) => s.sendMessage);
  const isStreaming = useChatStore((s) => s.isStreaming);

  // 决策来源优先级：从消息历史推断 > 本次会话内的本地提交（发送后、历史尚未刷新时）
  const effectiveDecision = decision ?? localDecision;
  const interactive = isLast && !effectiveDecision && !isStreaming;

  const submit = React.useCallback(
    async (text: string, kind: Exclude<WorkflowDecision, null>) => {
      setLocalDecision(kind);
      // 留一小段动画时间再发送，与 UserChoices 一致
      await new Promise((r) => setTimeout(r, 200));
      await sendMessage(text);
    },
    [sendMessage]
  );

  const handleSupplement = React.useCallback(() => {
    const text = supplement.trim();
    if (!text) return;
    void submit(`补充说明：${text}`, "edited");
  }, [supplement, submit]);

  // 输入框聚焦时自动增高，避免长文本被截断
  const handleTextareaChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setSupplement(e.target.value);
    const el = e.target;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  };

  // 折叠步骤时，若正在输入则不允许收起，避免输入内容被视觉隐藏
  const canCollapse = !supplement.trim();

  return (
    <section
      aria-label={`工作流确认：${data.title}`}
      className={cn(
        "mt-4 overflow-hidden rounded-xl border transition-colors motion-reduce:transition-none",
        "border-indigo-200 bg-indigo-50/40",
        "dark:border-indigo-900/60 dark:bg-indigo-950/20"
      )}
    >
      <Header data={data} decision={effectiveDecision} />

      <div className="space-y-3 bg-[var(--color-bg-container)] px-4 py-3">
        <p className="text-sm text-[var(--color-text-secondary)]">{data.description}</p>

        {/* 步骤列表：仅可交互状态提供折叠（历史回执/失效态固定展开，便于回顾） */}
        {data.steps.length > 4 && canCollapse && interactive ? (
          <button
            type="button"
            onClick={() => setShowSteps((v) => !v)}
            className="text-xs font-medium text-indigo-600 hover:underline dark:text-indigo-400"
          >
            {showSteps ? "收起步骤" : `展开全部 ${data.steps.length} 个步骤`}
          </button>
        ) : null}
        {showSteps || !canCollapse || !interactive ? (
          <ol className="space-y-2">
            {data.steps.map((step, idx) => (
              <StepItem key={idx} step={step} index={idx} />
            ))}
          </ol>
        ) : null}

        {data.notes ? (
          <p className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
            说明：{data.notes}
          </p>
        ) : null}

        {effectiveDecision ? (
          <DecisionReceipt decision={effectiveDecision} data={data} supplement={supplement} />
        ) : (
          <ActionArea
            interactive={interactive}
            isLast={Boolean(isLast)}
            supplement={supplement}
            textareaRef={textareaRef}
            onSupplementChange={handleTextareaChange}
            onConfirm={() => void submit("确认保存", "confirmed")}
            onCancel={() => void submit("取消", "cancelled")}
            onSupplementSubmit={handleSupplement}
          />
        )}
      </div>
    </section>
  );
}

/* ==========================================================================
   子组件
   ========================================================================== */

function Header({ data, decision }: { data: WorkflowConfirmData; decision: WorkflowDecision }) {
  const done = decision !== null;
  return (
    <div
      className={cn(
        "flex items-start gap-2.5 border-b px-4 py-2.5",
        done
          ? "border-[var(--color-border-secondary)] bg-[var(--color-fill-tertiary)]"
          : "border-indigo-100 bg-indigo-50 dark:border-indigo-900/60 dark:bg-indigo-950/40"
      )}
    >
      <WorkflowIcon
        className={cn(
          "mt-0.5 h-4 w-4 shrink-0",
          done ? "text-[var(--color-text-tertiary)]" : "text-indigo-500 dark:text-indigo-400"
        )}
      />
      <div className="min-w-0 flex-1">
        <p
          className={cn(
            "text-sm font-semibold",
            done ? "text-[var(--color-text-secondary)]" : "text-indigo-700 dark:text-indigo-300"
          )}
        >
          {done ? "工作流确认" : "发现可固定的工作流"}
        </p>
        <p className="mt-0.5 truncate text-xs text-[var(--color-text-tertiary)]">
          {data.title}（{data.name}）
        </p>
        {data.overwrite && !done ? (
          <p className="mt-1 flex items-center gap-1 text-xs font-medium text-amber-600 dark:text-amber-400">
            <AlertTriangle className="h-3 w-3 shrink-0" />
            已存在同名工作流，确认保存将覆盖更新原流程
          </p>
        ) : null}
      </div>
    </div>
  );
}

function StepItem({ step, index }: { step: WorkflowConfirmStep; index: number }) {
  return (
    <li className="flex gap-2.5 text-sm">
      <span
        className={cn(
          "mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-xs font-medium",
          "bg-indigo-100 text-indigo-600 dark:bg-indigo-900/60 dark:text-indigo-300"
        )}
      >
        {index + 1}
      </span>
      <div className="min-w-0 flex-1 space-y-1">
        {step.when ? (
          <span className="inline-flex items-center rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-950/60 dark:text-amber-300">
            条件：{step.when}
          </span>
        ) : null}
        <p className="text-[var(--color-text)]">{step.action}</p>
        {step.tool ? (
          <p className="font-mono text-xs text-[var(--color-text-tertiary)]">{step.tool}</p>
        ) : null}
      </div>
    </li>
  );
}

function DecisionReceipt({
  decision,
  data,
  supplement,
}: {
  decision: WorkflowDecision;
  data: WorkflowConfirmData;
  supplement: string;
}) {
  const meta: Record<
    Exclude<WorkflowDecision, null>,
    { icon: React.ReactNode; text: string; className: string }
  > = {
    confirmed: {
      icon: <CheckCircle2 className="h-4 w-4" />,
      text: data.overwrite ? `已确认覆盖保存「${data.title}」` : `已确认保存「${data.title}」`,
      className: "text-emerald-600 dark:text-emerald-400",
    },
    cancelled: {
      icon: <XCircle className="h-4 w-4" />,
      text: `已取消保存「${data.title}」`,
      className: "text-[var(--color-text-secondary)]",
    },
    edited: {
      icon: <Pencil className="h-4 w-4" />,
      text: supplement.trim()
        ? `已提交修改意见：${supplement.trim()}`
        : "已提交修改意见，Agent 正在按补充信息重新提取",
      className: "text-indigo-600 dark:text-indigo-400",
    },
  };
  const item = meta[decision as Exclude<WorkflowDecision, null>];

  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        "flex items-start gap-2 rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2.5 text-sm",
        item.className
      )}
    >
      <span className="mt-0.5 shrink-0">{item.icon}</span>
      <span className="min-w-0 break-words">{item.text}</span>
    </div>
  );
}

function ActionArea({
  interactive,
  isLast,
  supplement,
  textareaRef,
  onSupplementChange,
  onConfirm,
  onCancel,
  onSupplementSubmit,
}: {
  interactive: boolean;
  isLast: boolean;
  supplement: string;
  textareaRef: React.RefObject<HTMLTextAreaElement>;
  onSupplementChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
  onConfirm: () => void;
  onCancel: () => void;
  onSupplementSubmit: () => void;
}) {
  if (!interactive) {
    return (
      <p className="text-xs text-[var(--color-text-tertiary)]">
        {isLast ? "生成中，请稍候…" : "该确认已失效（草稿可能已过期）"}
      </p>
    );
  }

  return (
    <div className="space-y-3 pt-1">
      {/* 主次分层的操作区：主操作实心、取消为文字按钮，降低误触 */}
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={onConfirm}
          className={cn(
            "inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3.5 py-2 text-sm font-medium text-white",
            "transition-colors hover:bg-emerald-700 active:scale-[0.98] motion-reduce:transition-none",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 focus-visible:ring-offset-2",
            "dark:bg-emerald-600 dark:hover:bg-emerald-500 dark:ring-offset-[var(--color-bg-container)]"
          )}
        >
          <Check className="h-4 w-4" />
          确认保存
        </button>
        <button
          type="button"
          onClick={onCancel}
          className={cn(
            "rounded-lg px-3 py-2 text-sm font-medium text-[var(--color-text-secondary)]",
            "transition-colors hover:bg-[var(--color-fill-tertiary)] hover:text-[var(--color-text)]",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--color-border)]"
          )}
        >
          取消
        </button>
      </div>

      {/* 修改意见：常驻可见，无需二次点击展开 */}
      <div className="space-y-2">
        <div className="flex items-end gap-2">
          <textarea
            ref={textareaRef}
            value={supplement}
            onChange={onSupplementChange}
            onKeyDown={(e) => {
              if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                onSupplementSubmit();
              }
            }}
            rows={1}
            placeholder="需要调整？直接输入修改意见，Agent 会重新提取"
            aria-label="修改意见"
            className={cn(
              "min-h-[38px] w-full resize-none rounded-lg border px-3 py-2 text-sm leading-snug",
              "border-[var(--color-border)] bg-[var(--color-bg-container)] text-[var(--color-text)]",
              "placeholder:text-[var(--color-text-tertiary)]",
              "focus:border-indigo-400 focus:outline-none focus:ring-2 focus:ring-indigo-100",
              "dark:focus:border-indigo-600 dark:focus:ring-indigo-950"
            )}
          />
          {supplement.trim() ? (
            <button
              type="button"
              onClick={onSupplementSubmit}
              aria-label="提交修改意见"
              className={cn(
                "mb-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg",
                "bg-indigo-600 text-white transition-colors hover:bg-indigo-700 active:scale-[0.97]",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2"
              )}
            >
              <ArrowUp className="h-4 w-4" />
            </button>
          ) : null}
        </div>
        {supplement.trim() ? (
          <p className="text-xs text-[var(--color-text-tertiary)]">
            提交后 Agent 将按修改意见重新提取流程（⌘/Ctrl + Enter）
          </p>
        ) : null}
      </div>
    </div>
  );
}
