export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export interface User {
  userId: string;
  username?: string;
  role: string;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
}

export interface Citation {
  id: string;
  text: string;
  score: number;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
  agentSteps?: AgentStep[];
  citations?: Citation[];
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
}

export interface McpCallPayload {
  toolId: string;
  status: "executing" | "completed" | "failed";
  error?: string;
}

export interface AgentStepPayload {
  iteration: number;
  action: "TOOL_CALL" | "FINISH" | "ERROR";
  plan?: string;
  thought: string;
  toolName?: string;
  toolInput?: Record<string, unknown>;
  observation?: string;
  finalAnswer?: string;
  durationMs: number;
}

export interface AgentStep {
  iteration: number;
  plan?: string;
  thought: string;
  action: "TOOL_CALL" | "FINISH" | "ERROR";
  toolName?: string;
  toolInput?: Record<string, unknown>;
  observation?: string;
  durationMs: number;
  collapsed: boolean;
}
