package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrievalEngine;
import com.byteq.ai.ragstudio.rag.core.rewrite.RewriteResult;
import com.byteq.ai.ragstudio.rag.dto.RetrievalContext;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具
 * <p>
 * 将现有的 {@link RetrievalEngine} 封装为 Agent 可调用的 {@link Tool}，
 * 使 Agent 在 ReACT 循环中可主动发起知识库检索，而非仅在预处理阶段被调用。
 * <p>
 * 使用方式：
 * <pre>{@code
 *   RagSearchTool tool = new RagSearchTool(retrievalEngine, searchProperties, knowledgeBaseIds);
 *   tool.execute(Map.of("query", "年假申请流程", "topK", 5));
 * }</pre>
 * <p>
 * 注意：此工具非单例，每次 Agent 调用时使用对应请求的知识库 ID 列表构造。
 */
@Slf4j
public class RagSearchTool implements Tool {

    private static final String TOOL_NAME = "rag_search";
    private static final String TOOL_DESCRIPTION =
            "搜索知识库获取相关文档内容。当需要查找规章制度、技术文档、操作手册等存储于知识库中的信息时使用此工具。";

    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final List<String> knowledgeBaseIds;

    /**
     * @param retrievalEngine  检索引擎
     * @param searchProperties 检索配置（TopK 等）
     * @param knowledgeBaseIds 当前请求选择的知识库 ID 列表（空列表表示无知识库可用）
     */
    public RagSearchTool(RetrievalEngine retrievalEngine,
                         SearchChannelProperties searchProperties,
                         List<String> knowledgeBaseIds) {
        this.retrievalEngine = retrievalEngine;
        this.searchProperties = searchProperties;
        this.knowledgeBaseIds = knowledgeBaseIds != null ? List.copyOf(knowledgeBaseIds) : List.of();
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(
                "object",
                Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "搜索查询语句，应使用关键词或自然语言描述要查找的内容"
                        ),
                        "topK", Map.of(
                                "type", "integer",
                                "description", "返回结果数量上限",
                                "default", 5
                        )
                ),
                List.of("query"),
                null,
                null,
                null
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return ToolResult.failure(TOOL_NAME, "未选择知识库，无法检索。请告知用户需要先选择知识库。");
        }

        String query = params != null && params.get("query") instanceof String q && !q.isBlank()
                ? q : null;
        if (query == null) {
            return ToolResult.failure(TOOL_NAME, "缺少必填参数: query（搜索查询语句）");
        }

        int topK = params.containsKey("topK") && params.get("topK") instanceof Number n
                ? n.intValue()
                : searchProperties.getDefaultTopK();
        if (topK <= 0) {
            topK = searchProperties.getDefaultTopK();
        }

        try {
            // 使用 RewriteResult 兼容现有检索 API，直接以 query 作为主问题
            RewriteResult rewriteResult = new RewriteResult(query, List.of(query));
            RetrievalContext ctx = retrievalEngine.retrieveByKnowledgeBases(
                    knowledgeBaseIds, rewriteResult, topK);

            if (ctx == null || StrUtil.isBlank(ctx.getKbContext())) {
                return ToolResult.success(TOOL_NAME, "未检索到与 \"" + query + "\" 相关的文档。");
            }
            return ToolResult.success(TOOL_NAME, ctx.getKbContext());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("RAG 检索失败: query={}, error={}", query, errorMsg);
            return ToolResult.failure(TOOL_NAME, "检索执行失败: " + errorMsg);
        }
    }
}
