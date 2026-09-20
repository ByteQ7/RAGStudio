export type Role = "user" | "assistant" | "tool" | "observation";

/** 账号角色：admin=管理员，user=普通用户 */
export type UserRole = "admin" | "user";

export type FeedbackValue = "like" | "dislike" | null;

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export interface UserChoiceOption {
  text: string;
  /** 选项说明（可选）：帮助用户理解该选项的后果，渲染为按钮下方小字 */
  description?: string;
}

export interface UserChoiceData {
  options: UserChoiceOption[];
}

export interface User {
  userId: string;
  username?: string;
  role: UserRole;
  token: string;
  avatar?: string;
}

export type CurrentUser = Omit<User, "token">;

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
  /** 所属对话分组 ID，null/undefined 表示未分组 */
  groupId?: string | null;
}

/** 对话分组（元宝式：可设置分组专属指令与默认知识库，组内新对话自动套用） */
export interface ConversationGroup {
  id: string;
  /** 后端返回的分组 ID 字段（部分接口用 groupId，前端归一化为 id 时兼容读取） */
  groupId?: string;
  name: string;
  instruction?: string | null;
  /** 是否置顶 */
  pinned?: boolean;
  /** 分组默认知识库 ID 列表（组内对话默认选中，可手动增删） */
  knowledgeBaseIds?: string[];
  conversationCount?: number;
  createTime?: string;
}

/** 引用来源类型：KB=知识库文档，WEB=网络搜索 */
export type CitationSourceType = "KB" | "WEB";

export interface Citation {
  id: string;
  chunkId?: string;
  text: string;
  score: number;
  kbName?: string;
  docName?: string;
  contentType?: string;
  imageUrl?: string;
  /** 引用来源类型，缺省视为 KB（兼容历史数据） */
  sourceType?: CitationSourceType;
  /** WEB 来源网站链接 */
  url?: string;
  /** WEB 结果标题 */
  title?: string;
  /** WEB 来源搜索引擎 */
  engine?: string;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  /** 思考过程内容（reasoning/thinking 通道累积），独立于正文展示 */
  thinking?: string;
  /** 思考耗时（秒），后端持久化值 */
  thinkingDurationSeconds?: number;
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
  agentSteps?: AgentStep[];
  citations?: Citation[];
  imageUrls?: string[];
  thinkingLevel?: number;
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
  planSteps?: string[];
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
  planSteps?: string[];
  thought: string;
  action: "TOOL_CALL" | "FINISH" | "ERROR";
  toolName?: string;
  toolInput?: Record<string, unknown>;
  observation?: string;
  durationMs: number;
  collapsed: boolean;
}
