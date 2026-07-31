package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrievalEngine;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDefinition;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.core.skill.ToolReaderTool;
import com.byteq.ai.ragstudio.rag.core.skill.SkillTool;
import com.byteq.ai.ragstudio.rag.core.skill.SandboxExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Q&A 子 Agent（A2A 架构）
 * <p>
 * 基于企业内部知识库回答问题的专用 Agent。包装现有的 {@link AgentLoop}，
 * 构建专用于问答的工具集，运行标准 ReACT 循环并透传 SSE 事件。
 * </p>
 *
 * <h3>输入 Task 参数</h3>
 * <ul>
 *   <li>{@code question} (String) — 用户问题</li>
 * </ul>
 *
 * <h3>产出 Artifact</h3>
 * <ul>
 *   <li>{@code type="answer"} — 最终回答文本</li>
 *   <li>{@code type="steps"} — Agent 推理步骤（AgentStep 列表）</li>
 *   <li>{@code type="citations"} — 引用溯源数据</li>
 * </ul>
 */
@Slf4j
public class QaSubAgent implements SubAgent {

    private static final AgentCard CARD = new AgentCard(
            "qa",
            "基于企业内部知识库回答员工关于公司制度、流程规范、政策文档等的问题。可以检索知识库、调用工具、读取用户自定义 SKILL。",
            List.of("kb_retrieval", "mcp_tools", "skill_tools")
    );

    private final LLMService llmService;
    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final McpToolRegistry mcpToolRegistry;
    private final SkillLoader skillLoader;
    private final okhttp3.OkHttpClient syncHttpClient;
    private final SandboxExecutor sandboxExecutor;
    private final ReActResponseParser reactResponseParser;
    private final ReActPromptBuilder reactPromptBuilder;
    private final com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader promptTemplateLoader;
    private final Supplier<Boolean> cancellationChecker;
    private final List<String> knowledgeBaseIds;
    private final String kbSummaryText;
    private final String rewrittenQuery;

    private final com.byteq.ai.ragstudio.rag.service.RagTraceRecordService traceRecordService;

    private final ToolRetriever toolRetriever;

    private final List<RetrievedChunk> retrievedChunks = new ArrayList<>();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final java.util.regex.Pattern CHUNK_REF_PATTERN =
            java.util.regex.Pattern.compile("\\[\\^chunk_(\\w+)\\]");

    public QaSubAgent(
            LLMService llmService,
            RetrievalEngine retrievalEngine,
            SearchChannelProperties searchProperties,
            McpToolRegistry mcpToolRegistry,
            SkillLoader skillLoader,
            okhttp3.OkHttpClient syncHttpClient,
            SandboxExecutor sandboxExecutor,
            ReActResponseParser reactResponseParser,
            ReActPromptBuilder reactPromptBuilder,
            com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader promptTemplateLoader,
            Supplier<Boolean> cancellationChecker,
            List<String> knowledgeBaseIds,
            String kbSummaryText,
            String rewrittenQuery,
            com.byteq.ai.ragstudio.rag.service.RagTraceRecordService traceRecordService,
            ToolRetriever toolRetriever
    ) {
        this.llmService = llmService;
        this.retrievalEngine = retrievalEngine;
        this.searchProperties = searchProperties;
        this.mcpToolRegistry = mcpToolRegistry;
        this.skillLoader = skillLoader;
        this.syncHttpClient = syncHttpClient;
        this.sandboxExecutor = sandboxExecutor;
        this.reactResponseParser = reactResponseParser;
        this.reactPromptBuilder = reactPromptBuilder;
        this.promptTemplateLoader = promptTemplateLoader;
        this.cancellationChecker = cancellationChecker;
        this.knowledgeBaseIds = knowledgeBaseIds != null ? knowledgeBaseIds : List.of();
        this.kbSummaryText = kbSummaryText;
        this.rewrittenQuery = rewrittenQuery;
        this.traceRecordService = traceRecordService;
        this.toolRetriever = toolRetriever;
    }

    @Override
    public AgentCard getCard() { return CARD; }

    @Override
    public List<Artifact> run(Task task, AgentContext ctx, StreamCallback callback) {
        // 每次新对话清空旧的检索结果，避免跨轮对话的引用污染
        retrievedChunks.clear();

        // 1. 构建工具集（按用户问题语义检索，仅注入相关工具）
        ToolRegistry toolRegistry = buildToolRegistry(ctx.getQuestion());

        // 2. 创建并运行 AgentLoop
        AgentLoop agentLoop = new AgentLoop(
                llmService, toolRegistry,
                reactResponseParser, reactPromptBuilder, promptTemplateLoader,
                cancellationChecker, ctx.getThinkingLevel()
        );

        // 3. 引用溯源回调（直接接收 streamFinalAnswer 中的 finalAnswer 参数，不依赖 callback.getAnswerString）
        agentLoop.setBeforeCompleteCallback((finalAnswer) -> {
            fireCitations(callback, finalAnswer);
        });

        // 4. 执行 Agent 循环
        agentLoop.run(ctx, callback);

        // 5. 收集 Artifact
        List<Artifact> artifacts = new ArrayList<>();
        String answer = callback instanceof com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler
                ? ((com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler) callback).getAnswerString()
                : "";

        // answer artifact
        artifacts.add(new Artifact(task.getId(), CARD.name(), "answer", answer != null ? answer : ""));

        // steps artifact
        artifacts.add(new Artifact(task.getId(), CARD.name(), "steps",
                ctx.getSteps().stream().map(AgentStep::toString).toList()));

        // citations artifact（如有）
        if (!retrievedChunks.isEmpty()) {
            artifacts.add(new Artifact(task.getId(), CARD.name(), "citations",
                    retrievedChunks.stream().map(c -> Map.of(
                            "id", c.getId(),
                            "text", c.getText(),
                            "score", c.getScore(),
                            "kbName", c.getKbName(),
                            "docName", c.getDocName()
                    )).toList()));
        }

        return artifacts;
    }

    private ToolRegistry buildToolRegistry(String question) {
        ToolRegistry registry = new ToolRegistry();
        registry.setTraceRecordService(traceRecordService);

        List<String> relevantTools = toolRetriever.retrieve(question);
        boolean hasRetrieval = CollUtil.isNotEmpty(relevantTools);
        if (hasRetrieval) {
            log.info("语义检索命中工具: {}", relevantTools);
        }

        // 全部 MCP 工具都注册，prompt 注入时按 relevance 筛选
        for (McpToolExecutor executor : mcpToolRegistry.listAllExecutors()) {
            registry.register(new McpToolAdapter(executor));
        }
        registry.register(new TimeTool());

        RagSearchTool ragTool = new RagSearchTool(retrievalEngine, searchProperties,
                knowledgeBaseIds, kbSummaryText, question, rewrittenQuery);
        ragTool.setChunksConsumer(chunks -> {
            for (RetrievedChunk chunk : chunks) {
                if (chunk.getId() != null) {
                    boolean exists = retrievedChunks.stream()
                            .anyMatch(c -> chunk.getId().equals(c.getId()));
                    if (!exists) retrievedChunks.add(chunk);
                }
            }
        });
        registry.register(ragTool);

        registry.register(new ToolReaderTool(skillLoader, mcpToolRegistry));

        List<SkillDefinition> skills = skillLoader.getAllSkills();
        for (SkillDefinition def : skills) {
            registry.register(new SkillTool(def, syncHttpClient, sandboxExecutor));
        }

        log.info("Q&A Agent 工具: MCP={}/{}, RAG=1, SKILL={}/{}, 内置=1, 总计={}",
                mcpToolRegistry.listAllExecutors().stream()
                        .filter(e -> !hasRetrieval || relevantTools.contains(e.getToolDefinition().name()))
                        .count(),
                mcpToolRegistry.size(),
                skills.stream().filter(s -> !hasRetrieval || relevantTools.contains(s.getName())).count(),
                skills.size(), registry.size());
        return registry;
    }

    private void fireCitations(StreamCallback callback, String finalAnswer) {
        if (retrievedChunks.isEmpty() && StrUtil.isBlank(finalAnswer)) return;
        try {
            List<String> referencedIds = new ArrayList<>();
            if (StrUtil.isNotBlank(finalAnswer)) {
                java.util.regex.Matcher m = CHUNK_REF_PATTERN.matcher(finalAnswer);
                while (m.find()) {
                    String id = m.group(1);
                    if (!referencedIds.contains(id)) referencedIds.add(id);
                }
            }

            List<Map<String, Object>> citations = new ArrayList<>();
            if (!referencedIds.isEmpty()) {
                for (String id : referencedIds) {
                    RetrievedChunk c = null;
                    try {
                        int idx = Integer.parseInt(id) - 1;
                        if (idx >= 0 && idx < retrievedChunks.size()) {
                            c = retrievedChunks.get(idx);
                        }
                    } catch (NumberFormatException ignored) {
                        for (RetrievedChunk rc : retrievedChunks) {
                            if (id.equals(rc.getId())) { c = rc; break; }
                        }
                    }
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("id", id);
                    entry.put("chunkId", c != null && c.getId() != null ? c.getId() : "");
                    entry.put("text", c != null && c.getText() != null ? c.getText() : "");
                    entry.put("score", c != null && c.getScore() != null ? c.getScore() : 0f);
                    entry.put("kbName", c != null && c.getKbName() != null ? c.getKbName() : "");
                    entry.put("docName", c != null && c.getDocName() != null ? c.getDocName() : "");
                    entry.put("contentType", c != null && c.getContentType() != null ? c.getContentType() : "TEXT");
                    entry.put("imageUrl",
                            c != null && c.getMetadata() != null && c.getMetadata().get("image_url") instanceof String imgUrl
                                    ? imgUrl : "");
                    if (c == null && retrievedChunks.isEmpty()) continue;
                    citations.add(entry);
                }
            } else {
                for (RetrievedChunk c : retrievedChunks) {
                    if (c.isImage()) {
                        Map<String, Object> entry = new java.util.LinkedHashMap<>();
                        entry.put("id", c.getId() != null ? c.getId() : "");
                        entry.put("chunkId", c.getId() != null ? c.getId() : "");
                        entry.put("text", "");
                        entry.put("score", c.getScore() != null ? c.getScore() : 0f);
                        entry.put("kbName", c.getKbName() != null ? c.getKbName() : "");
                        entry.put("docName", c.getDocName() != null ? c.getDocName() : "");
                        entry.put("contentType", c.getContentType() != null ? c.getContentType() : "IMAGE");
                        entry.put("imageUrl",
                                c.getMetadata() != null && c.getMetadata().get("image_url") instanceof String imgUrl
                                        ? imgUrl : "");
                        citations.add(entry);
                        continue;
                    }
                    if (StrUtil.isBlank(c.getText())) continue;
                    String chunkText = c.getText();
                    boolean matched = false;
                    for (int i = 0; i <= chunkText.length() - 10 && !matched; i++) {
                        if (StrUtil.isNotBlank(finalAnswer) && finalAnswer.contains(chunkText.substring(i, i + 10))) {
                            matched = true;
                        }
                    }
                    if (matched) {
                        Map<String, Object> entry = new java.util.LinkedHashMap<>();
                        entry.put("id", c.getId() != null ? c.getId() : "");
                        entry.put("chunkId", c.getId() != null ? c.getId() : "");
                        entry.put("text", c.getText());
                        entry.put("score", c.getScore() != null ? c.getScore() : 0f);
                        entry.put("kbName", c.getKbName() != null ? c.getKbName() : "");
                        entry.put("docName", c.getDocName() != null ? c.getDocName() : "");
                        entry.put("contentType", c.getContentType() != null ? c.getContentType() : "TEXT");
                        entry.put("imageUrl",
                                c.getMetadata() != null && c.getMetadata().get("image_url") instanceof String imgUrl
                                        ? imgUrl : "");
                        citations.add(entry);
                    }
                }
            }

            if (!citations.isEmpty()) {
                String json = OBJECT_MAPPER.writeValueAsString(citations);
                callback.onCitation(json);
            }
        } catch (Exception e) {
            log.warn("Q&A 引用溯源失败", e);
        }
    }
}
