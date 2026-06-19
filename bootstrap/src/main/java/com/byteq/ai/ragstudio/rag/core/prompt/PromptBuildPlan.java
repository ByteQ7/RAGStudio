package com.byteq.ai.ragstudio.rag.core.prompt;

import lombok.Builder;
import lombok.Data;

/**
 * Prompt 构建计划，封装一次提示词构建所需的场景、基模板及各上下文数据
 */
@Data
@Builder
public class PromptBuildPlan {

    /**
     * 构建场景（KB_ONLY / MCP_ONLY / MIXED / EMPTY）
     */
    private PromptScene scene;

    /**
     * 选用的基模板内容（由 plan 阶段决定，为空时使用场景默认模板）
     */
    private String baseTemplate;

    /**
     * MCP 工具调用返回的上下文文本（已格式化）
     */
    private String mcpContext;

    /**
     * 知识库检索返回的上下文文本（已格式化）
     */
    private String kbContext;

    /**
     * 用户原始问题
     */
    private String question;
}
