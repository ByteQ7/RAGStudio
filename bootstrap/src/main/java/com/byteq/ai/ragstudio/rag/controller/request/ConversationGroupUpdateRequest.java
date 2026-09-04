package com.byteq.ai.ragstudio.rag.controller.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 对话分组更新请求（部分更新语义）
 * <p>
 * 字段为 null 表示不修改该项；instruction / knowledgeBaseIds 传空值表示清除。
 * </p>
 */
@Data
public class ConversationGroupUpdateRequest {

    /**
     * 分组名称（null = 不修改；非空时校验长度）
     */
    @Size(max = 64, message = "分组名称长度不能超过64个字符")
    private String name;

    /**
     * 分组专属指令（null = 不修改；空串 = 清除指令）
     */
    @Size(max = 2000, message = "分组指令长度不能超过2000个字符")
    private String instruction;

    /**
     * 是否置顶（null = 不修改）
     */
    private Boolean pinned;

    /**
     * 分组默认知识库 ID 列表（null = 不修改；空列表 = 清除）
     */
    private List<String> knowledgeBaseIds;
}
