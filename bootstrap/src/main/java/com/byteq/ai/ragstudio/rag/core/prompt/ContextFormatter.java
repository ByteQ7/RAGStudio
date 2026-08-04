package com.byteq.ai.ragstudio.rag.core.prompt;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.List;
import java.util.Map;

/**
 * 上下文格式化器，负责将知识库检索结果和 MCP 工具调用结果格式化为可嵌入 Prompt 的文本
 */
public interface ContextFormatter {

    /**
     * 格式化知识库检索上下文
     *
     * @param chunksByKey 按 key 分组的检索文档块
     * @param topK        每个 key 下保留的最大文档块数量
     * @return 格式化后的知识库上下文文本
     */
    default String formatKbContext(Map<String, List<RetrievedChunk>> chunksByKey, int topK) {
        return formatKbContext(chunksByKey, topK, 0);
    }

    /**
     * 格式化知识库检索上下文（带引用编号起始偏移）
     *
     * @param chunksByKey    按 key 分组的检索文档块
     * @param topK           每个 key 下保留的最大文档块数量
     * @param citationStartIndex 引用编号起始偏移（0 表示从头编号）。
     *                           Agent 多次调用 rag_search 时，偏移为已累计的 chunk 数，
     *                           使 [^chunk_N] 编号跨多次检索全局唯一，引用溯源才能按位置精确映射。
     * @return 格式化后的知识库上下文文本
     */
    String formatKbContext(Map<String, List<RetrievedChunk>> chunksByKey, int topK, int citationStartIndex);

    /**
     * 格式化 MCP 工具调用上下文
     *
     * @param results MCP 工具调用结果，按工具名称分组
     * @return 格式化后的 MCP 上下文文本
     */
    String formatMcpContext(Map<String, List<CallToolResult>> results);
}
