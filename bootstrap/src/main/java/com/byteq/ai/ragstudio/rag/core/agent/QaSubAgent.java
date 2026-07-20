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
import com.byteq.ai.ragstudio.rag.core.skill.SkillReaderTool;
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
    private final Supplier<Boolean> cancellationChecker;
    private final List<String> knowledgeBaseIds;
    private final String kbSummaryText;
    private final Map<String, String> collectionNameToKbId;

    private final com.byteq.ai.ragstudio.rag.service.RagTraceRecordService traceRecordService;

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
            Supplier<Boolean> cancellationChecker,
            List<String> knowledgeBaseIds,
            String kbSummaryText,
            Map<String, String> collectionNameToKbId,
            com.byteq.ai.ragstudio.rag.service.RagTraceRecordService traceRecordService
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
        this.cancellationChecker = cancellationChecker;
        this.knowledgeBaseIds = knowledgeBaseIds != null ? knowledgeBaseIds : List.of();
        this.kbSummaryText = kbSummaryText;
        this.collectionNameToKbId = collectionNameToKbId != null ? collectionNameToKbId : Map.of();
        this.traceRecordService = traceRecordService;
    }

    @Override
    public AgentCard getCard() { return CARD; }

    @Override
    public List<Artifact> run(Task task, AgentContext ctx, StreamCallback callback) {
        // 1. 构建工具集
        ToolRegistry toolRegistry = buildToolRegistry();

        // 2. 创建并运行 AgentLoop
        AgentLoop agentLoop = new AgentLoop(
                llmService, toolRegistry,
                reactResponseParser, reactPromptBuilder,
                cancellationChecker, ctx.getThinkingLevel()
        );

        // 3. 引用溯源回调
        agentLoop.setBeforeCompleteCallback(() -> {
            String finalAnswer = callback instanceof com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler
                    ? ((com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler) callback).getAnswerString()
                    : null;
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

    private ToolRegistry buildToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setTraceRecordService(traceRecordService);

        for (McpToolExecutor executor : mcpToolRegistry.listAllExecutors()) {
            registry.register(new McpToolAdapter(executor));
        }
        registry.register(new TimeTool());

        if (CollUtil.isNotEmpty(knowledgeBaseIds)) {
            RagSearchTool ragTool = new RagSearchTool(retrievalEngine, searchProperties, knowledgeBaseIds, kbSummaryText, collectionNameToKbId);
            ragTool.setChunksConsumer(chunks -> {
                retrievedChunks.clear();
                retrievedChunks.addAll(chunks);
            });
            registry.register(ragTool);
        }

        registry.register(new SkillReaderTool(skillLoader));

        List<SkillDefinition> skills = skillLoader.getAllSkills();
        for (SkillDefinition def : skills) {
            registry.register(new SkillTool(def, syncHttpClient, sandboxExecutor));
        }

        log.info("Q&A Agent 工具: MCP={}, RAG={}, SKILL={}, 内置=1, 总计={}",
                mcpToolRegistry.size(),
                CollUtil.isNotEmpty(knowledgeBaseIds) ? 1 : 0,
                skills.size(), registry.size());
        return registry;
    }

    private void fireCitations(StreamCallback callback, String finalAnswer) {
        if (retrievedChunks.isEmpty()) return;
        List<String> referencedIds = new ArrayList<>();
        if (StrUtil.isNotBlank(finalAnswer)) {
            java.util.regex.Matcher m = CHUNK_REF_PATTERN.matcher(finalAnswer);
            while (m.find()) referencedIds.add(m.group(1));
        }
        try {
            String json = OBJECT_MAPPER.writeValueAsString(retrievedChunks.stream()
                    .filter(c -> StrUtil.isBlank(finalAnswer) || (c.getId() != null && referencedIds.contains(c.getId())))
                    .map(c -> Map.of("id", c.getId() != null ? c.getId() : "",
                            "text", c.getText() != null ? c.getText() : "",
                            "score", c.getScore() != null ? c.getScore() : 0f,
                            "kbName", c.getKbName() != null ? c.getKbName() : "",
                            "docName", c.getDocName() != null ? c.getDocName() : ""))
                    .toList());
            callback.onCitation(json);
        } catch (Exception e) {
            log.warn("Q&A 引用溯源失败", e);
        }
    }
}
