import * as React from "react";
import { useNavigate, useParams } from "react-router-dom";

import { ChatInput } from "@/components/chat/ChatInput";
import { KnowledgeBaseSelector } from "@/components/chat/KnowledgeBaseSelector";
import { MessageList } from "@/components/chat/MessageList";
import { MainLayout } from "@/components/layout/MainLayout";
import { useChatStore } from "@/stores/chatStore";

export function ChatPage() {
  const navigate = useNavigate();
  const { sessionId } = useParams<{ sessionId: string }>();
  const {
    messages,
    isLoading,
    isStreaming,
    currentSessionId,
    sessions,
    isCreatingNew,
    knowledgeBaseIds,
    setKnowledgeBaseIds,
    fetchSessions,
    selectSession,
    createSession
  } = useChatStore();
  const [sessionsReady, setSessionsReady] = React.useState(false);
  const sessionExists = React.useMemo(() => {
    if (!sessionId) return false;
    return sessions.some((session) => session.id === sessionId);
  }, [sessionId, sessions]);

  React.useEffect(() => {
    let active = true;
    fetchSessions()
      .catch(() => null)
      .finally(() => {
        if (active) {
          setSessionsReady(true);
        }
      });
    return () => {
      active = false;
    };
  }, [fetchSessions]);

  React.useEffect(() => {
    if (sessionId) {
      if (sessionsReady && !sessionExists) {
        createSession().catch(() => null);
        navigate("/chat", { replace: true });
        return;
      }
      selectSession(sessionId).catch(() => null);
      return;
    }
    if (!sessionsReady) {
      return;
    }
    if (isCreatingNew) {
      return;
    }
    if (currentSessionId) {
      return;
    }
    createSession().catch(() => null);
  }, [
    sessionId,
    sessionsReady,
    sessionExists,
    isCreatingNew,
    currentSessionId,
    selectSession,
    createSession,
    navigate
  ]);

  React.useEffect(() => {
    if (currentSessionId && currentSessionId !== sessionId) {
      navigate(`/chat/${currentSessionId}`, { replace: true });
    }
  }, [currentSessionId, sessionId, navigate]);

  return (
    <MainLayout>
      <div className="flex h-full flex-col" style={{ background: 'linear-gradient(180deg, #f8f9fc 0%, #f0f1ff 50%, #f8f9fc 100%)' }}>
        <div className="flex-1 min-h-0">
          <MessageList
            messages={messages}
            isLoading={isLoading}
            isStreaming={isStreaming}
            sessionKey={currentSessionId}
          />
        </div>
        {(messages.length > 0 || isLoading) && (
          <div className="relative z-20">
            <div className="absolute inset-0 bg-gradient-to-t from-[#f8f9fc] via-[#f8f9fc]/80 to-transparent h-6 -top-6 pointer-events-none" />
            <div className="mx-auto max-w-[760px] px-6 pt-2 pb-4">
              <div className="mb-2">
                <KnowledgeBaseSelector
                  selectedKnowledgeBaseIds={knowledgeBaseIds}
                  onKnowledgeBaseIdsChange={setKnowledgeBaseIds}
                />
              </div>
              <ChatInput />
            </div>
          </div>
        )}
      </div>
    </MainLayout>
  );
}
