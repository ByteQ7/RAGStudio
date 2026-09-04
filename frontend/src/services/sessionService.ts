import { api } from "@/services/api";
import type { ConversationGroup } from "@/types";

export interface ConversationVO {
  conversationId: string;
  title: string;
  groupId?: string | null;
  lastTime?: string;
}

export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  thinkingContent?: string | null;
  thinkingLevel?: number | null;
  thinkingDuration?: number | null;
  agentSteps?: string | null;
  citations?: string | null;
  imageUrls?: string | null;
  vote: number | null;
  createTime?: string;
}

export async function listSessions() {
  return api.get<ConversationVO[], ConversationVO[]>("/conversations");
}

export async function deleteSession(conversationId: string) {
  return api.delete<void>(`/conversations/${conversationId}`);
}

export async function batchDeleteSessions(ids: string[]) {
  if (ids.length === 0) return;
  if (ids.length === 1) {
    await deleteSession(ids[0]);
    return;
  }
  return api.request<void>({
    url: "/conversations/batch",
    method: "DELETE",
    data: ids
  });
}

export async function renameSession(conversationId: string, title: string) {
  return api.put<void>(`/conversations/${conversationId}`, { title });
}

export async function listMessages(conversationId: string) {
  return api.get<ConversationMessageVO[], ConversationMessageVO[]>(`/conversations/${conversationId}/messages`);
}

// ==================== 对话分组（元宝式） ====================

export async function listConversationGroups() {
  return api.get<ConversationGroup[], ConversationGroup[]>("/conversation-groups");
}

export async function createConversationGroup(name: string) {
  return api.post<ConversationGroup, ConversationGroup>("/conversation-groups", { name });
}

/**
 * 更新分组（部分更新语义）
 * - 字段缺省（undefined）= 不修改
 * - instruction 传 "" = 清除指令
 * - knowledgeBaseIds 传 [] = 清除默认知识库
 */
export interface ConversationGroupUpdatePayload {
  name?: string;
  instruction?: string;
  pinned?: boolean;
  knowledgeBaseIds?: string[];
}

export async function updateConversationGroup(groupId: string, payload: ConversationGroupUpdatePayload) {
  return api.put<void>(`/conversation-groups/${groupId}`, payload);
}

export async function deleteConversationGroup(groupId: string) {
  return api.delete<void>(`/conversation-groups/${groupId}`);
}

/**
 * 批量移动会话到分组；groupId 传 null 表示移出分组
 */
export async function moveSessionsToGroup(conversationIds: string[], groupId: string | null) {
  return api.put<void>("/conversations/group", {
    conversationIds,
    groupId: groupId ?? undefined
  });
}
