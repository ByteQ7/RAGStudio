import * as React from "react";
import { X } from "lucide-react";

import { AgentSteps } from "@/components/chat/AgentSteps";
import { CitationList } from "@/components/chat/CitationList";
import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { LocationRequest } from "@/components/chat/LocationRequest";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { ThinkingPanel } from "@/components/chat/ThinkingPanel";
import { UserChoices } from "@/components/chat/UserChoices";
import {
  WorkflowConfirm,
  type WorkflowConfirmData,
  type WorkflowDecision,
} from "@/components/chat/WorkflowConfirm";
import { Avatar } from "@/components/common/Avatar";
import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";
import { useAuthStore } from "@/stores/authStore";
import type { Message, UserChoiceData, UserChoiceOption } from "@/types";

// ==================== 标记解析工具 ====================

interface ParsedMarkers {
  /** 移除标记后的纯文本 */
  cleanContent: string;
  /** 是否包含位置请求标记 */
  hasLocationRequest: boolean;
  /** 用户选项数据（如有） */
  userChoice: UserChoiceData | null;
  /** 工作流确认数据（如有） */
  workflowConfirm: WorkflowConfirmData | null;
}

/**
 * 解析 AI 消息内容中的协议标记
 * - [LOCATION_REQUEST] — 位置请求
 * - [USER_CHOICE]...[/USER_CHOICE] — 用户选项
 * - [WORKFLOW_CONFIRM]...[/WORKFLOW_CONFIRM] — 工作流确认卡片（JSON 载荷）
 */
function parseMessageMarkers(content: string): ParsedMarkers {
  let cleanContent = content;

  // 检测位置请求标记
  const hasLocationRequest = cleanContent.includes("[LOCATION_REQUEST]");
  cleanContent = cleanContent.replace("[LOCATION_REQUEST]", "");

  // 检测用户选项块
  // 语法：每行一个选项，可选用「 | 」追加说明（如：确认保存 | 立即生效）
  const choiceMatch = cleanContent.match(/\[USER_CHOICE\]([\s\S]*?)\[\/USER_CHOICE\]/);
  let userChoice: UserChoiceData | null = null;
  if (choiceMatch) {
    const options: UserChoiceOption[] = choiceMatch[1]
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line.length > 0)
      .map((line) => {
        const sep = line.indexOf("|");
        if (sep <= 0) {
          return { text: line };
        }
        const text = line.slice(0, sep).trim();
        const description = line.slice(sep + 1).trim();
        return description ? { text, description } : { text };
      })
      .filter((opt) => opt.text.length > 0);
    if (options.length > 0) {
      userChoice = { options };
    }
    cleanContent = cleanContent.replace(choiceMatch[0], "");
  }

  // 检测工作流确认块（JSON 载荷，解析失败则忽略标记）
  const workflowMatch = cleanContent.match(/\[WORKFLOW_CONFIRM\]([\s\S]*?)\[\/WORKFLOW_CONFIRM\]/);
  let workflowConfirm: WorkflowConfirmData | null = null;
  if (workflowMatch) {
    try {
      const parsed = JSON.parse(workflowMatch[1].trim()) as WorkflowConfirmData;
      if (parsed && parsed.name && Array.isArray(parsed.steps)) {
        workflowConfirm = parsed;
      }
    } catch {
      // 载荷损坏：不渲染卡片，同时把标记从正文剔除
    }
    cleanContent = cleanContent.replace(workflowMatch[0], "");
  }

  return { cleanContent, hasLocationRequest, userChoice, workflowConfirm };
}

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
  /** 该消息之后的第一条用户消息（确认卡片回执态推断用） */
  nextUserMessage?: string;
}

/**
 * 从用户后续回复推断工作流确认卡片的决策（回执态）。
 * 与后端 WorkflowSaveTool 的语义判定保持一致：确认保存 / 取消 / 其余视为修改意见。
 */
function inferWorkflowDecision(reply: string | undefined): WorkflowDecision {
  if (!reply) return null;
  const text = reply.trim();
  if (!text) return null;
  if (text === "确认保存" || /^(确认|确定|保存|固定|好的?|可以|同意|没问题|yes|y|ok|okay|sure)$/i.test(text)) {
    return "confirmed";
  }
  if (text === "取消" || /^(取消|不保存|不要|算了|no|not)$/i.test(text)) {
    return "cancelled";
  }
  if (text.startsWith("补充说明：")) {
    return "edited";
  }
  return null;
}

export const MessageItem = React.memo(function MessageItem({
  message,
  isLast,
  nextUserMessage,
}: MessageItemProps) {
  const { user } = useAuthStore();
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !hasContent;
  const [expandedImage, setExpandedImage] = React.useState<string | null>(null);
  const contentRef = React.useRef<HTMLDivElement | null>(null);

  // 解析协议标记（仅在助理消息中处理）
  const { cleanContent, hasLocationRequest, userChoice, workflowConfirm } = React.useMemo(
    () =>
      !isUser
        ? parseMessageMarkers(message.content)
        : {
            cleanContent: message.content,
            hasLocationRequest: false,
            userChoice: null,
            workflowConfirm: null,
          },
    [message.content, isUser]
  );

  if (isUser) {
    const hasImages = message.imageUrls && message.imageUrls.length > 0;
    return (
      <div className="flex justify-end gap-2.5">
        <div className="user-message space-y-2">
          {message.content && <MarkdownRenderer content={message.content} compact />}
          {hasImages && (
            <div className="flex flex-wrap gap-2">
              {message.imageUrls!.map((url, idx) => (
                <button
                  key={idx}
                  type="button"
                  onClick={() => setExpandedImage(url)}
                  className="overflow-hidden rounded-lg border transition-shadow hover:shadow-tertiary focus:outline-none focus:ring-2 focus:ring-ring"
                  style={{ borderColor: 'var(--color-border-secondary)' }}
                >
                  <img
                    src={url}
                    alt={`图片 ${idx + 1}`}
                    className="h-20 w-20 object-cover sm:h-24 sm:w-24"
                    loading="lazy"
                  />
                </button>
              ))}
            </div>
          )}
        </div>
        <Avatar
          name={user?.username || "用户"}
          src={user?.avatar}
          className="mt-1 h-10 w-10 shrink-0 rounded-lg"
        />

        {/* 图片灯箱 */}
        {expandedImage && (
          <div
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm"
            onClick={() => setExpandedImage(null)}
            onKeyDown={(e) => e.key === "Escape" && setExpandedImage(null)}
            role="dialog"
            aria-modal="true"
            aria-label="图片预览"
          >
            <button
              type="button"
              onClick={() => setExpandedImage(null)}
              className="absolute right-4 top-4 flex h-9 w-9 items-center justify-center rounded-full text-white transition-colors"
              style={{ background: 'rgba(255,255,255,0.15)' }}
            >
              <X className="h-5 w-5" />
            </button>
            <img
              src={expandedImage}
              alt="预览大图"
              className="max-h-[90vh] max-w-[90vw] rounded-xl object-contain shadow-2xl"
              onClick={(e) => e.stopPropagation()}
            />
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="group">
      <div className="flex gap-2.5">
        <div className="flex mt-0.5 h-10 w-10 shrink-0 items-center justify-center rounded-lg border" style={{ borderColor: 'var(--color-border-secondary)', background: 'var(--color-fill-quaternary)' }}>
          <RAGStudioLogo className="h-6 w-6 text-indigo-500" />
        </div>
        <div className="min-w-0 max-w-[92%] space-y-3">
          <ThinkingPanel
            thinking={message.thinking}
            durationSeconds={message.thinkingDurationSeconds}
            streaming={message.status === "streaming"}
          />
          {message.agentSteps && message.agentSteps.length > 0 ? (
            <AgentSteps steps={message.agentSteps} thinkingLevel={message.thinkingLevel} />
          ) : null}
          <div className="space-y-2">
            {isWaiting ? (
              <div className="ai-wait" aria-label="思考中">
                <span className="ai-wait-dots" aria-hidden="true">
                  <span className="ai-wait-dot" />
                  <span className="ai-wait-dot" />
                  <span className="ai-wait-dot" />
                </span>
              </div>
            ) : null}
            {hasContent ? (
              <div ref={contentRef} className="[&_.prose]:text-[18px] leading-relaxed" style={{ color: 'var(--color-text)' }}>
                <MarkdownRenderer content={cleanContent} citations={message.citations} />
                {/* 位置请求组件：仅当这是最后一条消息时才触发 */}
                {/* 历史消息中的 [LOCATION_REQUEST] 已有后续位置回复，不再重定位 */}
                {hasLocationRequest && !isUser && isLast && (
                  <LocationRequest />
                )}
                {/* 用户选项组件：已选择时渲染只读回执；历史消息不可交互（问题已翻篇） */}
                {userChoice && !isUser && (
                  <UserChoices
                    options={userChoice.options}
                    selectedText={nextUserMessage}
                    disabled={!isLast}
                  />
                )}
                {/* 工作流确认卡片：仅最后一条消息可交互；已有后续回复时渲染只读回执 */}
                {workflowConfirm && !isUser && (
                  <WorkflowConfirm
                    data={workflowConfirm}
                    isLast={Boolean(isLast)}
                    decision={inferWorkflowDecision(nextUserMessage)}
                  />
                )}
              </div>
            ) : null}
            {message.status === "error" ? (
              <p className="text-xs text-rose-500">生成已中断。</p>
            ) : null}
            {message.role === "assistant" && message.status === "done" ? (
              <CitationList message={message} />
            ) : null}
            {showFeedback ? (
              <FeedbackButtons
                messageId={message.id}
                feedback={message.feedback ?? null}
                content={message.content}
                alwaysVisible={Boolean(isLast)}
              />
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
});
