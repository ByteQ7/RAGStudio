import * as React from "react";
import { X } from "lucide-react";

import { AgentSteps } from "@/components/chat/AgentSteps";
import { CitationList } from "@/components/chat/CitationList";
import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { Avatar } from "@/components/common/Avatar";
import { RAGStudioLogo } from "@/components/common/RAGStudioLogo";
import { useAuthStore } from "@/stores/authStore";
import type { Message } from "@/types";

interface MessageItemProps {
  message: Message;
  isLast?: boolean;
}

export const MessageItem = React.memo(function MessageItem({ message, isLast }: MessageItemProps) {
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
                  className="overflow-hidden rounded-lg border border-gray-200 transition-shadow hover:shadow-md focus:outline-none focus:ring-2 focus:ring-indigo-300"
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
              className="absolute right-4 top-4 flex h-9 w-9 items-center justify-center rounded-full bg-white/20 text-white hover:bg-white/30 transition-colors"
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
        <div className="flex mt-0.5 h-10 w-10 shrink-0 items-center justify-center rounded-lg border border-gray-200 bg-gray-50">
          <RAGStudioLogo className="h-6 w-6 text-indigo-500" />
        </div>
        <div className="min-w-0 max-w-[92%] space-y-3">
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
    </div>
  );
});
