import { create } from "zustand";
import { toast } from "sonner";

import type { AgentStep, AgentStepPayload, CompletionPayload, FeedbackValue, McpCallPayload, Message, MessageDeltaPayload, Session } from "@/types";
import {
  listMessages,
  listSessions,
  deleteSession as deleteSessionRequest,
  renameSession as renameSessionRequest
} from "@/services/sessionService";
import { stopTask, submitFeedback } from "@/services/chatService";
import { createStreamResponse } from "@/hooks/useStreamResponse";
import { API_BASE_URL } from "@/services/api";
import { storage } from "@/utils/storage";

interface ChatState {
  sessions: Session[];
  currentSessionId: string | null;
  messages: Message[];
  isLoading: boolean;
  sessionsLoaded: boolean;
  inputFocusKey: number;
  isStreaming: boolean;
  isStopping: boolean;
  isCreatingNew: boolean;
  knowledgeBaseIds: string[];
  deepThinkingLevel: number;
  setDeepThinkingLevel: (level: number) => void;
  streamTaskId: string | null;
  streamAbort: (() => void) | null;
  streamingMessageId: string | null;
  cancelRequested: boolean;
  fetchSessions: () => Promise<void>;
  createSession: () => Promise<string>;
  deleteSession: (sessionId: string) => Promise<void>;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  selectSession: (sessionId: string) => Promise<void>;
  updateSessionTitle: (sessionId: string, title: string) => void;
  setKnowledgeBaseIds: (ids: string[]) => void;
  sendMessage: (content: string, imageUrls?: string[], previewUrls?: string[]) => Promise<void>;
  cancelGeneration: () => void;
  appendStreamContent: (delta: string) => void;
  submitFeedback: (messageId: string, feedback: FeedbackValue) => Promise<void>;
  /** 内部方法：缓冲播放完毕时由定时器调用 */
  setIsStreamingFalse?: () => void;
}

/** 前端模拟流式输出的字符缓冲与定时器 */
let streamingBuffer = "";
let streamingTimer: number | null = null;

/** 定位当前正在流式输出的消息 */
function findStreamingMessage(messages: Message[]): { idx: number; msg: Message } | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role === "assistant" && (m.status === "streaming" || m.status === "done")) {
      return { idx: i, msg: m };
    }
  }
  return null;
}

function startStreamingTimer(get: () => ChatState) {
  if (streamingTimer) return;
  streamingTimer = window.setInterval(() => {
    if (streamingBuffer.length === 0) {
      const state = get();
      // 缓冲空 + 后端已完成 → 流式结束
      if (state.streamingMessageId === null && state.isStreaming) {
        window.clearInterval(streamingTimer!);
        streamingTimer = null;
        state.setIsStreamingFalse?.();
      }
      return;
    }
    const take = Math.min(3, streamingBuffer.length);
    const chars = streamingBuffer.slice(0, take);
    streamingBuffer = streamingBuffer.slice(take);
    useChatStore.setState((prev) => {
      const found = findStreamingMessage(prev.messages);
      if (!found) return prev;
      if (found.msg.status === "cancelled" || found.msg.status === "error") return prev;
      const updated = [...prev.messages];
      updated[found.idx] = { ...found.msg, content: found.msg.content + chars };
      return { messages: updated };
    });
  }, 1);
}

/** 停止定时器，可选 flush 剩余缓冲 */
function stopStreamingTimer(flushRemaining = true) {
  if (streamingTimer) {
    window.clearInterval(streamingTimer!);
    streamingTimer = null;
  }
  if (flushRemaining && streamingBuffer.length > 0) {
    const remaining = streamingBuffer;
    streamingBuffer = "";
    useChatStore.setState((prev) => {
      const found = findStreamingMessage(prev.messages);
      if (!found) return prev;
      if (found.msg.status === "cancelled" || found.msg.status === "error") return prev;
      const updated = [...prev.messages];
      updated[found.idx] = { ...found.msg, content: found.msg.content + remaining };
      return { messages: updated };
    });
  }
}

function mapVoteToFeedback(vote?: number | null): FeedbackValue {
  if (vote === 1) return "like";
  if (vote === -1) return "dislike";
  return null;
}

function upsertSession(sessions: Session[], next: Session) {
  const index = sessions.findIndex((session) => session.id === next.id);
  const updated = [...sessions];
  if (index >= 0) {
    updated[index] = { ...sessions[index], ...next };
  } else {
    updated.unshift(next);
  }
  return updated.sort((a, b) => {
    const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
    const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
    return timeB - timeA;
  });
}

/** 安全解析 Agent 步骤 JSON，解析失败返回 undefined */
function parseAgentSteps(raw: unknown): AgentStep[] | undefined {
  if (!raw) return undefined;
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    return Array.isArray(parsed) ? parsed : undefined;
  } catch {
    return undefined;
  }
}

export const useChatStore = create<ChatState>((set, get) => ({
  sessions: [],
  currentSessionId: null,
  messages: [],
  isLoading: false,
  sessionsLoaded: false,
  inputFocusKey: 0,
  isStreaming: false,
  isStopping: false,
  isCreatingNew: false,
  knowledgeBaseIds: [],
  deepThinkingLevel: 0,
  setDeepThinkingLevel: (level) => set({ deepThinkingLevel: level }),
  streamTaskId: null,
  streamAbort: null,
  streamingMessageId: null,
  cancelRequested: false,
  fetchSessions: async () => {
    set({ isLoading: true });
    try {
      const data = await listSessions();
      const sessions = data
        .map((item) => ({
        id: item.conversationId,
        title: item.title || "新对话",
        lastTime: item.lastTime
        }))
        .sort((a, b) => {
          const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
          const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
          return timeB - timeA;
        });
      set({ sessions });
    } catch (error) {
      toast.error((error as Error).message || "加载会话失败");
    } finally {
      set({ isLoading: false, sessionsLoaded: true });
    }
  },
  createSession: async () => {
    const state = get();
    if (state.messages.length === 0 && !state.currentSessionId) {
      set({
        isCreatingNew: true,
        isLoading: false
      });
      return "";
    }
    if (state.isStreaming) {
      get().cancelGeneration();
    }
    set({
      currentSessionId: null,
      messages: [],
      isStreaming: false,
      isStopping: false,
      isLoading: false,
      isCreatingNew: true,
      knowledgeBaseIds: [],
      streamTaskId: null,
      streamAbort: null,
      streamingMessageId: null,
      cancelRequested: false
    });
    return "";
  },
  deleteSession: async (sessionId) => {
    try {
      await deleteSessionRequest(sessionId);
      set((state) => ({
        sessions: state.sessions.filter((session) => session.id !== sessionId),
        messages: state.currentSessionId === sessionId ? [] : state.messages,
        currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId
      }));
      toast.success("删除成功");
    } catch (error) {
      toast.error((error as Error).message || "删除会话失败");
    }
  },
  renameSession: async (sessionId, title) => {
    const nextTitle = title.trim();
    if (!nextTitle) return;
    try {
      await renameSessionRequest(sessionId, nextTitle);
      set((state) => ({
        sessions: state.sessions.map((session) =>
          session.id === sessionId ? { ...session, title: nextTitle } : session
        )
      }));
      toast.success("已重命名");
    } catch (error) {
      toast.error((error as Error).message || "重命名失败");
    }
  },
  selectSession: async (sessionId) => {
    if (!sessionId) return;
    if (get().currentSessionId === sessionId && get().messages.length > 0) return;
    if (get().isStreaming) {
      get().cancelGeneration();
    }
    set({
      isLoading: true,
      currentSessionId: sessionId,
      isCreatingNew: false,
      knowledgeBaseIds: []
    });
    try {
      const data = await listMessages(sessionId);
      if (get().currentSessionId !== sessionId) {
        set({ isLoading: false });
        return;
      }
      const mapped: Message[] = data.map((item) => ({
        id: String(item.id),
        role: item.role === "assistant" ? "assistant" : "user",
        content: item.content,
        createdAt: item.createTime,
        feedback: mapVoteToFeedback(item.vote),
        status: "done",
        agentSteps: item.agentSteps ? parseAgentSteps(item.agentSteps) : undefined,
        citations: item.citations
          ? (typeof item.citations === "string" ? JSON.parse(item.citations) : item.citations)
          : undefined,
        imageUrls: item.imageUrls
          ? (typeof item.imageUrls === "string" ? JSON.parse(item.imageUrls) : item.imageUrls)
          : undefined,
        thinkingLevel: item.thinkingLevel ?? undefined
      }));
      set({ messages: mapped });

    } catch (error) {
      toast.error((error as Error).message || "加载消息失败");
    } finally {
      if (get().currentSessionId !== sessionId) {
        set({ isLoading: false });
        return;
      }
      set({
        isLoading: false,
        isStreaming: false,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false
      });
    }
  },
  updateSessionTitle: (sessionId, title) => {
    set((state) => ({
      sessions: state.sessions.map((session) =>
        session.id === sessionId ? { ...session, title } : session
      )
    }));
  },
  sendMessage: async (content, imageUrls, previewUrls) => {
    const trimmed = content.trim();
    if (!trimmed && (!imageUrls || imageUrls.length === 0)) return;
    // 纯图片无文本时使用占位文本
    const questionText = trimmed || (imageUrls ? `[图片]` : "");
    if (get().isStreaming) return;
    const knowledgeBaseIds = get().knowledgeBaseIds;
    // 清除旧缓冲，防止残留字符泄漏到新消息
    streamingBuffer = "";
    if (streamingTimer) {
      window.clearInterval(streamingTimer!);
      streamingTimer = null;
    }
    const inputFocusKey = Date.now();

    const displayContent = trimmed || (imageUrls ? `[图片 ${imageUrls.length} 张]` : "");
    const userMessage: Message = {
      id: `user-${Date.now()}`,
      role: "user",
      content: displayContent,
      status: "done",
      createdAt: new Date().toISOString(),
      // 优先使用预览 URL（presigned HTTP），回退到原始 s3:// URL
      imageUrls: previewUrls && previewUrls.length > 0 ? previewUrls : (imageUrls && imageUrls.length > 0 ? imageUrls : undefined)
    };
    const assistantId = `assistant-${Date.now()}`;
    const currentThinkingLevel = get().deepThinkingLevel;
    const assistantMessage: Message = {
      id: assistantId,
      role: "assistant",
      content: "",
      status: "streaming",
      feedback: null,
      createdAt: new Date().toISOString(),
      thinkingLevel: currentThinkingLevel
    };

    set((state) => ({
      messages: [...state.messages, userMessage, assistantMessage],
      isStreaming: true,
      streamingMessageId: assistantId,
      inputFocusKey,
      streamTaskId: null,
      cancelRequested: false
    }));

    const conversationId = get().currentSessionId;
    const url = `${API_BASE_URL}/rag/v3/chat`;
    const token = storage.getToken();

    const handlers = {
      onMeta: (payload: { conversationId: string; taskId: string }) => {
        if (get().streamingMessageId !== assistantId) return;
        const nextId = payload.conversationId || get().currentSessionId;
        if (!nextId) return;
        const lastTime = new Date().toISOString();
        const existing = get().sessions.find((session) => session.id === nextId);
        set((state) => ({
          currentSessionId: nextId,
          isCreatingNew: false,
          streamTaskId: payload.taskId,
          sessions: upsertSession(state.sessions, {
            id: nextId,
            title: existing?.title || "新对话",
            lastTime
          })
        }));
        if (get().cancelRequested) {
          stopTask(payload.taskId).catch(() => null);
        }
      },
      onMcpCall: (payload: McpCallPayload) => {
        if (payload.status === "executing") {
          get().appendStreamContent(`\n\n> 🔍 正在查询实时数据 (${payload.toolId})...`);
        }
      },
      onAgentStep: (payload: AgentStepPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        set((state) => ({
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            const existing = message.agentSteps ?? [];
            // Find if there's already a step for this iteration
            const idx = existing.findIndex((s) => s.iteration === payload.iteration);
            const step: AgentStep = {
              iteration: payload.iteration,
              plan: payload.plan,
              planSteps: payload.planSteps,
              thought: payload.thought,
              action: payload.action,
              toolName: payload.toolName,
              toolInput: payload.toolInput,
              observation: payload.observation,
              durationMs: payload.durationMs,
              collapsed: false
            };
            if (idx >= 0) {
              // Update existing step (e.g. observation back-fill)
              const updated = [...existing];
              updated[idx] = { ...updated[idx], ...step };
              return { ...message, agentSteps: updated };
            }
            return { ...message, agentSteps: [...existing, step] };
          })
        }));
      },
      onMessage: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        if (payload.type !== "response") return;
        get().appendStreamContent(payload.delta);
      },
      onReject: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        get().appendStreamContent(payload.delta);
      },
      onCitation: (payload) => {
        // citation 可能在前端标记 done 之后到达，所以不用 streamingMessageId 过滤
        const msgId = get().streamingMessageId || assistantId;
        if (!msgId) return;
        set((state) => ({
          messages: state.messages.map((message) =>
            message.id === msgId
              ? { ...message, citations: payload }
              : message
          )
        }));
      },
      onFinish: (payload: CompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload) return;
        if (payload.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        const currentId = get().currentSessionId;
        if (currentId) {
          const lastTime = new Date().toISOString();
          const existingTitle =
            get().sessions.find((session) => session.id === currentId)?.title || "新对话";
          const nextTitle = payload.title || existingTitle;
          set((state) => ({
            sessions: upsertSession(state.sessions, {
              id: currentId,
              title: nextTitle,
              lastTime
            })
          }));
        }
        if (payload.messageId) {
          const msgId = String(payload.messageId);
          set((state) => ({
            messages: state.messages.map((message) =>
              message.id === state.streamingMessageId
                ? {
                    ...message,
                    id: msgId,
                    status: "done"
                  }
                : message
            )
          }));
        } else {
          set((state) => ({
            messages: state.messages.map((message) =>
              message.id === state.streamingMessageId
                ? {
                    ...message,
                    status: "done"
                  }
                : message
            )
          }));
        }
      },
      onCancel: (payload: CompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        stopStreamingTimer(true);
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        // content 已在后端持久化时写好，前端只需标记状态并保留已累积的内容
        // 若 abort 先到达则由 AbortError 处理器更新 content
        const nextId = payload?.messageId ? String(payload.messageId) : undefined;
        const cancelSuffix = "\n\n---\n> **对话被用户关闭** ❗";
        set((state) => ({
          isStreaming: false,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false,
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            return {
              ...message,
              id: nextId || message.id,
              content: message.content.trim()
                ? message.content + cancelSuffix
                : cancelSuffix.trim(),
              status: "cancelled"
            };
          })
        }));
      },
      onDone: () => {
        if (get().streamingMessageId !== assistantId) return;
        // 清 streamingMessageId 标记后端完成，但不改 isStreaming
        // 定时器检测到 streamingMessageId=null 且缓冲空后自动设 isStreaming=false
        set({
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        });
      },
      onTitle: (payload: { title: string }) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
      },
      onError: (error: Error) => {
        if (get().streamingMessageId !== assistantId) return;
        stopStreamingTimer();
        set((state) => ({
          isStreaming: false,
          streamTaskId: null,
          streamAbort: null,
          cancelRequested: false,
          messages: state.messages.map((message) =>
            message.id === state.streamingMessageId
              ? {
                  ...message,
                  status: "error"
                }
              : message
          )
        }));
        toast.error(error.message || "生成失败");
      }
    };

    const { start, cancel } = createStreamResponse(
      {
        url,
        method: "POST",
        body: {
          question: questionText,
          conversationId: conversationId || undefined,
          knowledgeBaseIds:
            knowledgeBaseIds && knowledgeBaseIds.length > 0 ? knowledgeBaseIds : undefined,
          deepThinkingLevel: get().deepThinkingLevel || undefined,
          imageUrls: imageUrls && imageUrls.length > 0 ? imageUrls : undefined
        },
        headers: token ? { Authorization: token } : undefined,
        retryCount: 1
      },
      handlers
    );

    set({ streamAbort: cancel });

    try {
      await start();
    } catch (error) {
      if ((error as Error).name === "AbortError") {
        // 用户主动取消：立即更新内容（防止 onCancel SSE 未到达）
        set((state) => ({
          isStreaming: false,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false,
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            // 如果 onCancel 已经处理过（内容已含标记），跳过
            if (message.content.includes("对话被用户关闭")) return { ...message, status: "cancelled" };
            const cancelSuffix = "\n\n---\n> **对话被用户关闭** ❗";
            return {
              ...message,
              content: message.content.trim()
                ? message.content + cancelSuffix
                : cancelSuffix.trim(),
              status: "cancelled"
            };
          })
        }));
        return;
      }
      handlers.onError?.(error as Error);
    } finally {
      if (get().streamingMessageId === assistantId) {
        set({
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        });
      }
    }
  },
  cancelGeneration: () => {
    const { isStreaming, streamTaskId, streamingMessageId } = get();
    if (!isStreaming) return;

    // 缓冲播放模式：streamingMessageId 已为 null（后端已完成）
    if (streamingMessageId === null) {
      // 强制 flush 剩余缓冲
      stopStreamingTimer(true);
      set({ isStopping: true });
      // 1.5~3s 后恢复
      const delay = 1500 + Math.random() * 1500;
      setTimeout(() => {
        set({ isStopping: false, isStreaming: false });
      }, delay);
      return;
    }

    // 后端传输中模式：正常取消逻辑
    set({ cancelRequested: true });
    if (streamTaskId) {
      stopTask(streamTaskId).catch(() => null);
    }
    get().streamAbort?.();
  },
  appendStreamContent: (delta) => {
    if (!delta) return;
    streamingBuffer += delta;
    startStreamingTimer(get);
  },
  setKnowledgeBaseIds: (ids) => set({ knowledgeBaseIds: ids }),
  setIsStreamingFalse: () => set({ isStreaming: false }),
  submitFeedback: async (messageId, feedback) => {
    const vote = feedback === "like" ? 1 : feedback === "dislike" ? -1 : null;
    const prev = get().messages.find((message) => message.id === messageId)?.feedback ?? null;
    set((state) => ({
      messages: state.messages.map((message) =>
        message.id === messageId ? { ...message, feedback } : message
      )
    }));
    if (vote === null) {
      toast.success("取消成功");
      return;
    }
    try {
      await submitFeedback(messageId, vote);
      toast.success(feedback === "like" ? "点赞成功" : "点踩成功");
    } catch (error) {
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId ? { ...message, feedback: prev } : message
        )
      }));
      toast.error((error as Error).message || "反馈保存失败");
    }
  }
}));
