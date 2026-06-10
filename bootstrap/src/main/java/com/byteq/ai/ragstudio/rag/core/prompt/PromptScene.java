package com.byteq.ai.ragstudio.rag.core.prompt;

/**
 * Prompt 构建场景枚举，根据检索来源（知识库 / MCP）确定系统提示词模板
 */
public enum PromptScene {

    /**
     * 仅命中知识库检索，使用企业知识库专用提示词模板
     */
    KB_ONLY,

    /**
     * 仅命中 MCP 工具调用，使用 MCP 专用提示词模板
     */
    MCP_ONLY,

    /**
     * 同时命中知识库和 MCP，使用混合提示词模板
     */
    MIXED,

    /**
     * 无任何检索命中，返回空提示词
     */
    EMPTY
}
