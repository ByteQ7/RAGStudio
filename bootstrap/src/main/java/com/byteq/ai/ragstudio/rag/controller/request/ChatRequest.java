package com.byteq.ai.ragstudio.rag.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 10000, message = "问题长度不能超过10000个字符")
    private String question;

    private String conversationId;

    /**
     * 对话分组 ID（可选）：在分组内新建对话时由前端携带，
     * 首条消息创建会话后自动归组，组指令随后续会话行 group_id 生效
     */
    private String groupId;

    private List<String> knowledgeBaseIds;

    /** 图片 S3 URL 列表（用于多模态识别） */
    private List<String> imageUrls;

    /** 深度思考级别（0-100），0 表示关闭 */
    private Integer deepThinkingLevel;
}
