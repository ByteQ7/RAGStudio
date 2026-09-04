package com.byteq.ai.ragstudio.rag.controller.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Set;

/**
 * 会话移动到分组请求
 * <p>
 * groupId 为 null 时表示移出分组（回到未分组区域）。
 * </p>
 */
@Data
public class ConversationGroupMoveRequest {

    @NotNull(message = "会话ID列表不能为空")
    private Set<String> conversationIds;

    /**
     * 目标分组 ID，null 或空表示移出分组
     */
    private String groupId;
}
