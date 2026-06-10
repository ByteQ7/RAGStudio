package com.byteq.ai.ragstudio.rag.core.prompt;

import cn.hutool.core.util.StrUtil;
import lombok.Builder;
import lombok.Data;

/**
 * Prompt 构建上下文，封装一次 RAG 请求中用于组装提示词的全部输入数据
 */
@Data
@Builder
public class PromptContext {

    /**
     * 用户原始问题
     */
    private String question;

    /**
     * MCP 工具调用返回的上下文文本（已格式化）
     */
    private String mcpContext;

    /**
     * 知识库检索返回的上下文文本（已格式化）
     */
    private String kbContext;

    /**
     * 是否包含 MCP 上下文
     */
    public boolean hasMcp() {
        return StrUtil.isNotBlank(mcpContext);
    }

    /**
     * 是否包含知识库上下文
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }
}
