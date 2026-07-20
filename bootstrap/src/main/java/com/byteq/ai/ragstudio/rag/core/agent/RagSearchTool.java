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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
            "搜索知识库获取相关文档内容。当需要查找规章制度、技术文档、操作手册等存储于知识库中的信息时使用此工具。"
            + " query 参数应使用多个关键词和同义词以充分匹配文档（如「年假」->「年假 休假 带薪年休假 请假」），不要只用 1-2 个词。";

    private final String kbSummaryText;
    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final List<String> knowledgeBaseIds;
    /** collectionName → kbId 映射，用于 AI 指定 collection 时只检索对应知识库 */
    private final Map<String, String> collectionNameToKbId;
    /** 检索到的 Chunk 回调（用于引用溯源） */
    private Consumer<List<RetrievedChunk>> chunksConsumer;

    /**
     * @param retrievalEngine  检索引擎
     * @param searchProperties 检索配置（TopK 等）
     * @param knowledgeBaseIds 当前请求选择的知识库 ID 列表（空列表表示无知识库可用）
     */
    public RagSearchTool(RetrievalEngine retrievalEngine,
                         SearchChannelProperties searchProperties,
                         List<String> knowledgeBaseIds) {
        this(retrievalEngine, searchProperties, knowledgeBaseIds, null, Map.of());
    }

    /**
     * @param retrievalEngine  检索引擎
     * @param searchProperties 检索配置（TopK 等）
     * @param knowledgeBaseIds 当前请求选择的知识库 ID 列表
     * @param kbSummaryText    知识库概要（如 "人事制度: HR制度文档, 技术文档: API文档"），用于工具描述中让 LLM 了解知识库内容
     */
    public RagSearchTool(RetrievalEngine retrievalEngine,
                         SearchChannelProperties searchProperties,
                         List<String> knowledgeBaseIds,
                         String kbSummaryText) {
        this(retrievalEngine, searchProperties, knowledgeBaseIds, kbSummaryText, Map.of());
    }

    /**
     * @param retrievalEngine       检索引擎
     * @param searchProperties      检索配置（TopK 等）
     * @param knowledgeBaseIds      当前请求选择的知识库 ID 列表
     * @param kbSummaryText         知识库概要，用于工具描述中让 LLM 了解知识库内容
     * @param collectionNameToKbId  collection名称 → 知识库ID 映射，用于 AI 指定 collection 时只检索对应知识库
     */
    public RagSearchTool(RetrievalEngine retrievalEngine,
                         SearchChannelProperties searchProperties,
                         List<String> knowledgeBaseIds,
                         String kbSummaryText,
                         Map<String, String> collectionNameToKbId) {
        this.retrievalEngine = retrievalEngine;
        this.searchProperties = searchProperties;
        this.knowledgeBaseIds = knowledgeBaseIds != null ? List.copyOf(knowledgeBaseIds) : List.of();
        this.kbSummaryText = kbSummaryText;
        this.collectionNameToKbId = collectionNameToKbId != null ? collectionNameToKbId : Map.of();
    }

    /** 设置 Chunk 收集回调（Agent 模式下用于引用溯源） */
    public void setChunksConsumer(Consumer<List<RetrievedChunk>> chunksConsumer) {
        this.chunksConsumer = chunksConsumer;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        if (StrUtil.isNotBlank(kbSummaryText)) {
            return TOOL_DESCRIPTION + "\n当前可选知识库：\n" + kbSummaryText;
        }
        return TOOL_DESCRIPTION;
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(
                "object",
                Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "搜索查询语句，应使用多个关键词和同义词以提高检索覆盖率"
                        ),
                        "topK", Map.of(
                                "type", "integer",
                                "description", "返回结果数量上限",
                                "default", 5
                        ),
                        "collection", Map.of(
                                "type", "string",
                                "description", "可选参数，指定要检索的知识库 collection 名称（如 finance-group-document）。不传则在所有当前可选知识库中搜索"
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

        // 如果 AI 指定了 collection，只检索对应的知识库
        List<String> targetKbIds = knowledgeBaseIds;
        String collection = params != null && params.get("collection") instanceof String c && !c.isBlank()
                ? c : null;
        if (collection != null) {
            String matchedKbId = collectionNameToKbId.get(collection);
            if (matchedKbId != null && knowledgeBaseIds.contains(matchedKbId)) {
                targetKbIds = List.of(matchedKbId);
                log.info("按 collection 过滤知识库: {} → kbId={}", collection, matchedKbId);
            } else {
                log.warn("AI 指定的 collection '{}' 不在当前可选知识库中，降级检索全部", collection);
            }
        }

        try {
            // 使用 RewriteResult 兼容现有检索 API，直接以 query 作为主问题
            RewriteResult rewriteResult = new RewriteResult(query, List.of(query));
            RetrievalContext ctx = retrievalEngine.retrieveByKnowledgeBases(
                    targetKbIds, rewriteResult, topK);

            if (ctx == null || StrUtil.isBlank(ctx.getKbContext())) {
                return ToolResult.success(TOOL_NAME, "未检索到与 \"" + query + "\" 相关的文档。");
            }

            // 回调 Chunk 收集器（引用溯源用）
            if (chunksConsumer != null && CollUtil.isNotEmpty(ctx.getChunks())) {
                chunksConsumer.accept(ctx.getChunks());
            }

            return ToolResult.success(TOOL_NAME, ctx.getKbContext());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("RAG 检索失败: query={}, error={}", query, errorMsg);
            return ToolResult.failure(TOOL_NAME, "检索执行失败: " + errorMsg);
        }
    }
}
