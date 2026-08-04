package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrievalEngine;
import com.byteq.ai.ragstudio.rag.core.rewrite.FollowUpQueryUtil;
import com.byteq.ai.ragstudio.rag.core.rewrite.RewriteResult;
import com.byteq.ai.ragstudio.rag.dto.RetrievalContext;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
            + " query 参数应使用多个关键词和同义词以充分匹配文档（如「年假」->「年假 休假 带薪年休假 请假」），不要只用 1-2 个词。"
            + " 如果用户输入的是代码、编号、ID、错误码等精确标识符，直接使用原值作为 query，不需要扩展关键词。";

    private final String kbSummaryText;
    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final List<String> knowledgeBaseIds;
    /** 用户的原始提问（未经改写，用于重排序阶段） */
    private final String userOriginalQuestion;
    /** 系统查询改写结果（含上下文补全与指代消解），追问/重试场景优先使用 */
    private final String rewrittenQuery;
    /** 检索到的 Chunk 回调（用于引用溯源） */
    private Consumer<List<RetrievedChunk>> chunksConsumer;

    /** 引用编号起始偏移提供者（Agent 模式下返回已累计的 chunk 数，保证编号跨多次检索全局唯一） */
    private java.util.function.IntSupplier citationStartIndexSupplier;

    /**
     * @param retrievalEngine  检索引擎
     * @param searchProperties 检索配置（TopK 等）
     * @param knowledgeBaseIds 当前请求选择的知识库 ID 列表（空列表表示无知识库可用）
     */
    public RagSearchTool(RetrievalEngine retrievalEngine,
                         SearchChannelProperties searchProperties,
                         List<String> knowledgeBaseIds) {
        this(retrievalEngine, searchProperties, knowledgeBaseIds, null, null, null);
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
        this(retrievalEngine, searchProperties, knowledgeBaseIds, kbSummaryText, null, null);
    }

    /**
     * @param retrievalEngine       检索引擎
     * @param searchProperties      检索配置（TopK 等）
     * @param knowledgeBaseIds      当前请求选择的知识库 ID 列表
     * @param kbSummaryText         知识库概要，用于工具描述中让 LLM 了解知识库内容
     * @param userOriginalQuestion  用户原始提问（未经改写，传递给重排序阶段使用）
     */
    public RagSearchTool(RetrievalEngine retrievalEngine,
                         SearchChannelProperties searchProperties,
                         List<String> knowledgeBaseIds,
                         String kbSummaryText,
                         String userOriginalQuestion) {
        this(retrievalEngine, searchProperties, knowledgeBaseIds, kbSummaryText, userOriginalQuestion, null);
    }

    /**
     * @param retrievalEngine       检索引擎
     * @param searchProperties      检索配置（TopK 等）
     * @param knowledgeBaseIds      当前请求选择的知识库 ID 列表
     * @param kbSummaryText         知识库概要，用于工具描述中让 LLM 了解知识库内容
     * @param userOriginalQuestion  用户原始提问（未经改写，传递给重排序阶段使用）
     * @param rewrittenQuery        系统查询改写结果（含上下文补全与指代消解），追问/重试场景优先使用
     */
    public RagSearchTool(RetrievalEngine retrievalEngine,
                         SearchChannelProperties searchProperties,
                         List<String> knowledgeBaseIds,
                         String kbSummaryText,
                         String userOriginalQuestion,
                         String rewrittenQuery) {
        this.retrievalEngine = retrievalEngine;
        this.searchProperties = searchProperties;
        this.knowledgeBaseIds = knowledgeBaseIds != null ? List.copyOf(knowledgeBaseIds) : List.of();
        this.kbSummaryText = kbSummaryText;
        this.userOriginalQuestion = userOriginalQuestion;
        this.rewrittenQuery = rewrittenQuery;
    }

    /** 设置 Chunk 收集回调（Agent 模式下用于引用溯源） */
    public void setChunksConsumer(Consumer<List<RetrievedChunk>> chunksConsumer) {
        this.chunksConsumer = chunksConsumer;
    }

    /** 设置引用编号起始偏移提供者（Agent 模式下为已累计的 chunk 数，用于上下文 [^chunk_N] 全局编号） */
    public void setCitationStartIndexSupplier(java.util.function.IntSupplier citationStartIndexSupplier) {
        this.citationStartIndexSupplier = citationStartIndexSupplier;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        StringBuilder sb = new StringBuilder(TOOL_DESCRIPTION);
        if (StrUtil.isNotBlank(rewrittenQuery)) {
            sb.append("\n【多轮对话】系统已结合对话历史完成查询改写（上下文补全 + 指代消解），本次检索基准查询为：\"")
                    .append(rewrittenQuery)
                    .append("\"。若用户只是追问/重试（如\"再试试\"、\"继续\"、\"重新回答\"），请直接使用基准查询，"
                            + "不要按字面扩展追问词；若用户提出了新的具体问题，可在基准查询基础上调整。");
        }
        if (StrUtil.isNotBlank(kbSummaryText)) {
            sb.append("\n当前可选知识库：\n").append(kbSummaryText);
        }
        return sb.toString();
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

        // 多轮追问/重试场景：LLM 字面扩展出的弱查询（如"再 试试 再试试"）替换为系统改写后的基准查询
        if (StrUtil.isNotBlank(rewrittenQuery) && FollowUpQueryUtil.isWeakFollowUp(query)) {
            log.info("rag_search 查询'{}'为弱追问短语，替换为改写基准查询: '{}'", query, rewrittenQuery);
            query = rewrittenQuery;
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
            int citationStartIndex = citationStartIndexSupplier != null ? citationStartIndexSupplier.getAsInt() : 0;
            RetrievalContext ctx = retrievalEngine.retrieveByKnowledgeBases(
                    knowledgeBaseIds, rewriteResult, topK, userOriginalQuestion, citationStartIndex);

            if (ctx == null || StrUtil.isBlank(ctx.getKbContext())) {
                return ToolResult.success(TOOL_NAME, "未检索到与 \"" + query + "\" 相关的文档。");
            }

            // 回调 Chunk 收集器（引用溯源用）
            if (chunksConsumer != null && CollUtil.isNotEmpty(ctx.getChunks())) {
                chunksConsumer.accept(ctx.getChunks());
            }

            if (CollUtil.isNotEmpty(ctx.getImageDataUris())) {
                ToolResult tr = ToolResult.success(TOOL_NAME, ctx.getKbContext(), ctx.getImageDataUris());
                tr.setS3ImageUrls(ctx.getS3ImageUrls());
                return tr;
            }
            return ToolResult.success(TOOL_NAME, ctx.getKbContext());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("RAG 检索失败: query={}, error={}", query, errorMsg);
            return ToolResult.failure(TOOL_NAME, "检索执行失败: " + errorMsg);
        }
    }
}
