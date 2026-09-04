package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 对话分组视图对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConversationGroupVO {

    /**
     * 分组ID
     */
    private String groupId;

    /**
     * 分组名称
     */
    private String name;

    /**
     * 分组专属指令（组内新对话自动注入系统提示）
     */
    private String instruction;

    /**
     * 是否置顶
     */
    private Boolean pinned;

    /**
     * 分组默认知识库 ID 列表（组内对话默认选中，可手动增删）
     */
    private List<String> knowledgeBaseIds;

    /**
     * 组内会话数量
     */
    private Long conversationCount;

    /**
     * 创建时间
     */
    private Date createTime;
}
