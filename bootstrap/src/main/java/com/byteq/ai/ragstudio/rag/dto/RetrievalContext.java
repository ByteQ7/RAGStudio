package com.byteq.ai.ragstudio.rag.dto;

import cn.hutool.core.util.StrUtil;
import lombok.Builder;
import lombok.Data;

/**
 * 检索上下文
 * <p>
 * 统一承载 MCP（Model Context Protocol）和 KB（Knowledge Base）两路检索的结果。
 * MCP 上下文来自实时数据工具调用，KB 上下文来自向量数据库的文档检索。
 * 下游 Prompt 组装阶段根据此上下文中是否有数据来决定如何构造 Prompt。
 * </p>
 */
@Data
@Builder
public class RetrievalContext {

    /**
     * MCP 召回的上下文文本
     * <p>
     * 通过 MCP 工具调用获取的实时数据，如天气、新闻等动态信息。
     * </p>
     */
    private String mcpContext;

    /**
     * KB 召回的上下文文本
     * <p>
     * 从向量数据库中检索到的知识库文档片段。
     * </p>
     */
    private String kbContext;

    /**
     * 检查是否存在 MCP 上下文
     *
     * @return true 表示有 MCP 上下文数据
     */
    public boolean hasMcp() {
        return StrUtil.isNotBlank(mcpContext);
    }

    /**
     * 检查是否存在 KB 上下文
     *
     * @return true 表示有 KB 上下文数据
     */
    public boolean hasKb() {
        return StrUtil.isNotBlank(kbContext);
    }

    /**
     * 检查是否没有任何上下文
     *
     * @return true 表示既无 MCP 也无 KB 上下文
     */
    public boolean isEmpty() {
        return !hasMcp() && !hasKb();
    }
}
