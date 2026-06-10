package com.byteq.ai.ragstudio.rag.eval;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 评测检索接口出参（纯检索证据，无 LLM 输出）
 */
@Data
@Builder
public class EvalResponse {

    /**
     * 召回的文档 ID 列表（业务 docId，已去重；通过 chunkId 反查 t_knowledge_chunk.docId）
     */
    private List<String> retrievedDocIds;

    /**
     * 召回的 chunk 主键列表（RetrievedChunk.id，已去重；用于调试）
     */
    private List<String> retrievedChunkIds;

    /**
     * 召回的 chunk 文本列表（与 retrievedChunkIds 顺序对应）
     */
    private List<String> retrievedContexts;

    /**
     * MCP 工具调用结果（无 MCP 分支时为空字符串）
     */
    private String mcpContext;

    /**
     * 是否走了 MCP 分支
     */
    private boolean hasMcp;

    /**
     * 是否走了 KB 检索
     */
    private boolean hasKb;

    /**
     * 总耗时（毫秒）
     */
    private long latencyMs;
}
