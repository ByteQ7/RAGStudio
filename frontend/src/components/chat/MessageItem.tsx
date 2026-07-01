import * as React from "react";

import { AgentSteps } from "@/components/chat/AgentSteps";
import { CitationList } from "@/components/chat/CitationList";
import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import type { Message } from "@/types";

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
}

export const MessageItem = React.memo(function MessageItem({ message, isLast }: MessageItemProps) {
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !hasContent;

  if (isUser) {
    return (
      <div className="flex justify-end">
        <div className="user-message">
          <MarkdownRenderer content={message.content} compact />
        </div>
      </div>
    );
  }

  return (
    <div className="group">
      <div className="max-w-[92%] space-y-3">
        {message.agentSteps && message.agentSteps.length > 0 ? (
          <AgentSteps steps={message.agentSteps} />
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
            <div className="text-[15px] leading-relaxed text-gray-800">
              <MarkdownRenderer content={message.content} citations={message.citations} />
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
  );
});
