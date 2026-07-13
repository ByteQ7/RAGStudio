package com.byteq.ai.ragstudio.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.core.agent.AgentContext;

import com.byteq.ai.ragstudio.rag.core.agent.AgentLoop;
import com.byteq.ai.ragstudio.rag.core.agent.KbRelevanceChecker;
import com.byteq.ai.ragstudio.rag.core.agent.McpToolAdapter;
import com.byteq.ai.ragstudio.rag.core.agent.RagSearchTool;
import com.byteq.ai.ragstudio.rag.core.agent.ReActPromptBuilder;
import com.byteq.ai.ragstudio.rag.core.agent.ReActResponseParser;
import com.byteq.ai.ragstudio.rag.core.agent.TimeTool;
import com.byteq.ai.ragstudio.rag.core.agent.ToolRegistry;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDefinition;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.core.skill.SkillReaderTool;
import com.byteq.ai.ragstudio.rag.core.skill.SkillTool;
import com.byteq.ai.ragstudio.rag.core.skill.SandboxExecutor;
import okhttp3.OkHttpClient;
import com.byteq.ai.ragstudio.rag.core.mcp.McpParameterExtractor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.memory.ConversationMemoryService;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptContext;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import com.byteq.ai.ragstudio.rag.core.prompt.RAGPromptService;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrievalEngine;
import com.byteq.ai.ragstudio.rag.core.rewrite.QueryRewriteService;
import com.byteq.ai.ragstudio.rag.core.rewrite.RewriteResult;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.dto.RetrievalContext;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler;
import com.byteq.ai.ragstudio.rag.service.handler.StreamTaskManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 流式对话流水线
 * <p>
 * 承载 RAG（检索增强生成）流式对话的核心业务编排逻辑。
 * 整个 RAG 对话流程按照以下阶段顺序执行：
 * </p>
 * <ol>
 *   <li><b>记忆加载（loadMemory）</b>：加载对话历史记录，构建会话上下文</li>
 *   <li><b>查询改写（rewriteQuery）</b>：对用户问题进行语义改写和多问句拆分</li>
 *   <li><b>知识检索（retrieve）</b>：根据用户选择的知识库从向量数据库检索相关文档</li>
 *   <li><b>MCP 工具执行</b>：若改写阶段决策需要 MCP，则执行工具并二次调用 LLM</li>
 *   <li><b>流式回答</b>：无需 MCP 时直接输出；需 MCP 时执行工具后二次调用 LLM</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    /** 检索通道配置，用于控制 TopK 等检索参数 */
    private final SearchChannelProperties searchProperties;

    /** 对话记忆服务，负责加载和管理对话历史 */
    private final ConversationMemoryService memoryService;

    /** 查询改写服务，负责对用户问题进行语义改写和拆分 */
    private final QueryRewriteService queryRewriteService;

    /** 检索引擎，负责从向量数据库检索相关内容 */
    private final RetrievalEngine retrievalEngine;

    /** 大语言模型服务，负责调用 LLM 生成回答 */
    private final LLMService llmService;

    /** RAG Prompt 构建服务，负责组装包含检索上下文的 Prompt */
    private final RAGPromptService promptBuilder;

    /** Prompt 模板加载器，负责加载各种提示词模板文件 */
    private final PromptTemplateLoader promptTemplateLoader;

    /** 流式任务管理器，负责管理流式对话任务的取消和状态跟踪 */
    private final StreamTaskManager taskManager;

    /** MCP 工具注册表，用于获取可用工具列表和调用工具 */
    private final McpToolRegistry mcpToolRegistry;

    /** MCP 参数提取器，用于从用户问题中提取工具调用参数 */
    private final McpParameterExtractor mcpParameterExtractor;

    /** 链路追踪记录服务，用于记录各阶段的执行耗时 */
    private final RagTraceRecordService traceRecordService;

    /** SKILL 加载器，从 Redis/本地提供用户自定义 Agent 工具 */
    private final SkillLoader skillLoader;

    /** HTTP 客户端，用于执行 http 类型的 SKILL */
    private final OkHttpClient syncHttpClient;

    /** Docker 沙箱执行器，用于安全执行 script/command 类型的 SKILL */
    private SandboxExecutor sandboxExecutor;

    @org.springframework.beans.factory.annotation.Value("${rag.skills.sandbox.image:sandbox}")
    private String sandboxImage;

    @org.springframework.beans.factory.annotation.Value("${rag.skills.sandbox.timeout-ms:30000}")
    private long sandboxTimeoutMs;

    @org.springframework.beans.factory.annotation.Value("${rag.skills.sandbox.memory:256m}")
    private String sandboxMemory;

    @org.springframework.beans.factory.annotation.Value("${rag.skills.sandbox.cpus:0.5}")
    private String sandboxCpus;

    @jakarta.annotation.PostConstruct
    public void initSandbox() {
        this.sandboxExecutor = SandboxExecutor.builder()
                .dockerCommand("sudo", "-n", "docker")
                .image(sandboxImage)
                .timeoutMs(sandboxTimeoutMs)
                .memory(sandboxMemory)
                .cpus(sandboxCpus)
                .build();
    }



    /** 链路追踪配置，用于判断是否启用追踪 */
    private final RagTraceProperties traceProperties;

    /** 知识库相关性判断器，Agent 模式下用于判断问题是否与所选知识库相关 */
    private final KbRelevanceChecker kbRelevanceChecker;

    /** 知识库 DAO，用于查询知识库名称和描述 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /** ReACT 响应解析器（无状态单例） */
    private final ReActResponseParser reactResponseParser;

    /** ReACT Prompt 构建器（无状态单例） */
    private final ReActPromptBuilder reactPromptBuilder;

    /** Agent 模式标识 */
    private static final String MODE_AGENT = "agent";

    private static final String MCP_CALL_PREFIX = "__MCP_CALL__:";

    /** 取消异常前缀标识，用于在 catch 中区分用户取消 vs 其他 IllegalStateException */
    private static final String CANCEL_MARKER = "任务已被用户取消";

    /** 线程安全的 ObjectMapper 单例，避免每次调用都创建新实例 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 用于从 LLM 回答中提取 [^chunk_{id}] 引用的正则 */
    private static final java.util.regex.Pattern CHUNK_REF_PATTERN =
            java.util.regex.Pattern.compile("\\[\\^chunk_(\\w+)\\]");

    /**
     * 执行流式对话流水线
     * <p>
     * 流程：记忆加载 → 查询改写（含 MCP 决策）→ 检索 → 回答
     * 同步阶段通过 traceNode 自动记录执行耗时到链路追踪系统。
     * 流式阶段仅记录开始日志（实际耗时由底层 StreamSpan 和 run-level trace 记录）。
     * </p>
     */
    public void execute(StreamChatContext ctx) {
        String taskId = ctx.getTaskId();

        try {
            doExecute(ctx);

            // 取最终回答（流式内容已拼接完整）
            String answer = ctx.getCallback() instanceof com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler
                    ? ((com.byteq.ai.ragstudio.rag.service.handler.StreamChatEventHandler) ctx.getCallback()).getAnswerString()
                    : null;

            // 记录对话上下文 + 回答（doExecute 内才加载了 history）
            java.util.List<com.byteq.ai.ragstudio.framework.convention.ChatMessage> history = ctx.getHistory();
            if (history != null && !history.isEmpty()) {
                for (com.byteq.ai.ragstudio.framework.convention.ChatMessage msg : history) {
                    String role = msg.getRole() != null ? msg.getRole().name() : "unknown";
                    String content = msg.getContent() != null ? msg.getContent() : "";
                    if (content.length() > 2000) {
                        content = content.substring(0, 2000) + "\n... [已截断 " + (content.length() - 2000) + " 字符]";
                    }
                }
            }

            if (answer != null && !answer.isEmpty()) {
            }
        } catch (IllegalStateException e) {
            // 仅静默处理用户取消：其他 IllegalStateException 仍需正常传播
            if (e.getMessage() != null && e.getMessage().startsWith(CANCEL_MARKER)) {
                log.info("流水线因任务取消而终止，任务ID：{}", taskId);
                return;
            }
            throw e;
        } catch (Exception e) {
            throw e;
        }
    }

    private void doExecute(StreamChatContext ctx) {
        doExecuteAgent(ctx);
    }

    // ==================== Agent 模式 ====================

    /**
     * Agent 模式执行流程：记忆加载 → 相关性判断 → 条件检索 → AgentLoop
     */
    private void doExecuteAgent(StreamChatContext ctx) {
        traceNode("记忆加载", "MEMORY", () -> {
            loadMemory(ctx);
            return null;
        });
        checkCancellation(ctx);

        // 1. 相关性判断 + KB 过滤
        List<String> effectiveKbIds = ctx.getKnowledgeBaseIds();
        boolean kbRelevant = false;
        if (CollUtil.isNotEmpty(ctx.getKnowledgeBaseIds())) {
            KbRelevanceChecker.KbInfoProvider infoProvider = kbIds -> {
                if (CollUtil.isEmpty(kbIds)) return List.of();
                List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                                .in(KnowledgeBaseDO::getId, kbIds)
                                .eq(KnowledgeBaseDO::getDeleted, 0));
                return kbList.stream()
                        .map(kb -> new KbRelevanceChecker.KbInfo(
                                kb.getId(), kb.getName(), kb.getDescription(), kb.getCollectionName()))
                        .toList();
            };

            KbRelevanceChecker.RelevanceResult relevance = traceNode("相关性判断", "KB_RELEVANCE", () ->
                    kbRelevanceChecker.check(ctx.getQuestion(), ctx.getKnowledgeBaseIds(), infoProvider));
            checkCancellation(ctx);

            kbRelevant = relevance.relevant();
            // LLM 指定了具体的相关知识库 → 只检索这些
            if (relevance.hasSpecificCollections()) {
                List<String> relevantCollections = relevance.relevantCollectionNames();
                // 从原始 KB IDs 中过滤出 collection 匹配的
                List<KnowledgeBaseDO> allKbs = knowledgeBaseMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                                .in(KnowledgeBaseDO::getId, ctx.getKnowledgeBaseIds())
                                .eq(KnowledgeBaseDO::getDeleted, 0));
                effectiveKbIds = allKbs.stream()
                        .filter(kb -> relevantCollections.contains(kb.getCollectionName()))
                        .map(KnowledgeBaseDO::getId)
                        .toList();
                if (effectiveKbIds.isEmpty()) {
                    log.warn("LLM 指定的相关知识库未匹配到任何 KB，降级使用全部");
                    effectiveKbIds = ctx.getKnowledgeBaseIds();
                }
                log.info("知识库相关性判断: relevant=true, 过滤后检索 {} 个知识库: {}",
                        effectiveKbIds.size(), relevantCollections);
            } else {
                log.info("知识库相关性判断: relevant={}, reasoning='{}'", kbRelevant, relevance.reasoning());
            }
        } else {
            log.info("未选择知识库，跳过检索");
        }

        // 2. 构建 ToolRegistry（MCP 工具 + 过滤后的 RAG 检索工具）
        List<String> finalKbIds = effectiveKbIds;
        ToolRegistry toolRegistry = traceNode("工具注册", "TOOL_REGISTRY", () -> buildAgentToolRegistry(ctx, finalKbIds));
        checkCancellation(ctx);

        // 3. 构建 AgentContext 并执行 AgentLoop（含图片 URL 支持）
        AgentContext agentCtx = new AgentContext(
                ctx.getQuestion(),
                ctx.getHistory(),
                "",        // kbContext：不预检索，Agent 自主调用 rag_search
                kbRelevant,
                toolRegistry.listAll(),
                10,        // maxIterations
                120_000L,  // timeoutMs
                ctx.getImageUrls()
        );

        // 构建 AgentLoop（per-request 实例，因为 ToolRegistry 是 per-request 的）
        AgentLoop agentLoop = new AgentLoop(llmService, toolRegistry,
                reactResponseParser, reactPromptBuilder,
                () -> taskManager.isCancelled(ctx.getTaskId()),
                ctx.getDeepThinkingLevel());

        // 在 Agent 完成回答但 SSE 连接关闭前，推送引用溯源
        agentLoop.setBeforeCompleteCallback(() -> {
            // 此时 final answer 已完整推送到 callback 中，提取用于过滤未引用的 chunks
            String finalAnswer = ctx.getCallback() instanceof StreamChatEventHandler ?
                    ((StreamChatEventHandler) ctx.getCallback()).getAnswerString() : null;
            fireCitations(ctx, finalAnswer);
        });

        traceNode("Agent循环", "AGENT_LOOP", () -> {
            agentLoop.run(agentCtx, ctx.getCallback());
            return null;
        });

        logPipelineComplete(ctx);
    }

    /**
     * 构建 Agent 模式下的 ToolRegistry
     * <p>
     * 包含四类工具：
     * <ol>
     *   <li>MCP 工具：通过 {@link McpToolAdapter} 包装现有 {@link McpToolExecutor}</li>
     *   <li>内置时间工具：{@link TimeTool}，零依赖</li>
     *   <li>RAG 检索工具：{@link RagSearchTool}，允许 Agent 在循环中主动检索知识库</li>
     *   <li>用户自定义 SKILL：从 skills 目录加载的 JSON 工具</li>
     * </ol>
     */
    private ToolRegistry buildAgentToolRegistry(StreamChatContext ctx, List<String> kbIds) {
        ToolRegistry registry = new ToolRegistry();

        // 1. MCP 工具
        for (McpToolExecutor executor : mcpToolRegistry.listAllExecutors()) {
            registry.register(new McpToolAdapter(executor));
        }

        // 2. 内置时间工具（始终注册，零依赖）
        registry.register(new TimeTool());

        // 3. RAG 检索工具（仅当选择了知识库时添加）
        if (CollUtil.isNotEmpty(kbIds)) {
            RagSearchTool ragTool = new RagSearchTool(
                    retrievalEngine, searchProperties, kbIds);
            // 收集检索到的 Chunk 用于引用溯源
            ragTool.setChunksConsumer(chunks -> ctx.setRetrievedChunks(chunks));
            registry.register(ragTool);
        }

        // 4. SKILL 阅读器（始终注册，让 LLM 能读取 SKILL 详情）
        registry.register(new SkillReaderTool(skillLoader));

        // 5. 用户自定义 SKILL
        List<SkillDefinition> skills = skillLoader.getAllSkills();
        for (SkillDefinition def : skills) {
            registry.register(new SkillTool(def, syncHttpClient, sandboxExecutor));
        }

        int skillCount = skills.size();
        log.info("Agent 工具注册完成 - MCP: {}, RAG: {}, SKILL: {}, 内置: 2, 总计: {}",
                mcpToolRegistry.size(),
                CollUtil.isNotEmpty(kbIds) ? 1 : 0,
                skillCount,
                registry.size());
        return registry;
    }

    // 加载对话历史记忆并将当前用户问题（含图片 URL）追加到上下文中
    private void loadMemory(StreamChatContext ctx) {
        ChatMessage userMsg = ChatMessage.user(ctx.getQuestion());
        if (CollUtil.isNotEmpty(ctx.getImageUrls())) {
            userMsg.setImageUrls(ctx.getImageUrls());
        }
        List<ChatMessage> history = memoryService.loadAndAppend(
                ctx.getConversationId(),
                ctx.getUserId(),
                userMsg
        );
        ctx.setHistory(history);
    }

    // RAG 管线已移除——所有检索由 Agent 通过 ToolRegistry 中的 RagSearchTool 自主管理

    // ==================== 引用溯源 ====================

    // 将检索到的 Chunk 列表推送到前端用于引用展示
    // 根据 LLM 回答中的 [^chunk_{id}] 标记过滤，只推送被实际引用的 Chunk
    private void fireCitations(StreamChatContext ctx, String finalAnswer) {
        List<RetrievedChunk> chunks = ctx.getRetrievedChunks();
        if (CollUtil.isEmpty(chunks)) return;

        // 解析 answer 中被引用的 chunk ID 集合
        java.util.Set<String> referencedIds = new java.util.HashSet<>();
        if (StrUtil.isNotBlank(finalAnswer)) {
            java.util.regex.Matcher matcher = CHUNK_REF_PATTERN.matcher(finalAnswer);
            while (matcher.find()) {
                referencedIds.add(matcher.group(1));
            }
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(chunks.stream()
                    .filter(chunk -> {
                        // finalAnswer 为空时不过滤（兼容旧逻辑）
                        if (StrUtil.isBlank(finalAnswer)) return true;
                        // 只保留被 [^chunk_{id}] 引用的 chunk
                        return chunk.getId() != null && referencedIds.contains(chunk.getId());
                    })
                    .map(chunk -> {
                        java.util.Map<String, Object> m = new java.util.HashMap<>();
                        m.put("id", chunk.getId() != null ? chunk.getId() : "");
                        m.put("text", chunk.getText() != null ? chunk.getText() : "");
                        m.put("score", chunk.getScore() != null ? chunk.getScore() : 0f);
                        m.put("kbName", chunk.getKbName() != null ? chunk.getKbName() : "");
                        m.put("docName", chunk.getDocName() != null ? chunk.getDocName() : "");
                        return m;
                    })
                    .toList());
            ctx.getCallback().onCitation(json);
        } catch (Exception e) {
            // 取消状态下不警告（SSE 连接已关闭）
            if (!taskManager.isCancelled(ctx.getTaskId())) {
                log.warn("推送引用溯源失败", e);
            }
        }
    }

    // ==================== 链路追踪 ====================

    private static final String TRACE_STATUS_RUNNING = "RUNNING";
    private static final String TRACE_STATUS_SUCCESS = "SUCCESS";
    private static final String TRACE_STATUS_ERROR = "ERROR";

    /**
     * 记录流水线阶段耗时到链路追踪系统
     * <p>
     * 在链路追踪启用时，为每个阶段创建一条 Node 记录，记录阶段名称、类型和耗时。
     * 同时输出 INFO 日志。如果追踪未启用或不在链路上下文中，仅输出日志。
     * trace DB 操作已通过异步执行器异步化，不会阻塞业务线程。
     * </p>
     *
     * @param name     阶段名称（如 "记忆加载"、"查询改写"）
     * @param type     阶段类型（如 MEMORY、REWRITE、RETRIEVE、MCP）
     * @param supplier 阶段执行逻辑
     * @param <T>      返回值类型
     * @return 阶段执行结果
     */
    private <T> T traceNode(String name, String type, Supplier<T> supplier) {
        String traceId = RagTraceContext.getTraceId();
        boolean tracing = traceProperties.isEnabled() && StrUtil.isNotBlank(traceId);

        String nodeId = null;
        long startMillis = System.currentTimeMillis();

        if (tracing) {
            nodeId = IdUtil.getSnowflakeNextIdStr();
            // startNode 已异步化，不会抛出异常；pushNode 为纯 ThreadLocal 操作，也不会失败
            traceRecordService.startNode(RagTraceNodeDO.builder()
                    .traceId(traceId)
                    .nodeId(nodeId)
                    .parentNodeId(RagTraceContext.currentNodeId())
                    .depth(RagTraceContext.depth())
                    .nodeType(type)
                    .nodeName(name)
                    .className(StreamChatPipeline.class.getName())
                    .status(TRACE_STATUS_RUNNING)
                    .startTime(new Date(startMillis))
                    .build());
            RagTraceContext.pushNode(nodeId);
        }

        try {
            T result = supplier.get();
            long duration = System.currentTimeMillis() - startMillis;
            log.info("流水线阶段 [{}] 完成，耗时 {}ms", name, duration);
            safeFinishNode(tracing, traceId, nodeId, TRACE_STATUS_SUCCESS, null, duration);
            return result;
        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - startMillis;
            // 用户取消导致的异常降级为 info，不刷 error
            if (ex instanceof IllegalStateException && ex.getMessage() != null && ex.getMessage().startsWith("任务已被用户取消")) {
                log.info("流水线阶段 [{}] 被取消，耗时 {}ms", name, duration);
            } else {
                log.error("流水线阶段 [{}] 失败，耗时 {}ms", name, duration, ex);
            }
            String errorMsg = ex.getClass().getSimpleName() + ": "
                    + StrUtil.blankToDefault(ex.getMessage(), "");
            safeFinishNode(tracing, traceId, nodeId, TRACE_STATUS_ERROR, errorMsg, duration);
            throw ex;
        } finally {
            if (tracing) {
                RagTraceContext.popNode();
            }
        }
    }

    /**
     * 完成 trace 节点记录
     * <p>
     * finishNode 已异步化，不会阻塞当前线程，也不会抛出异常。
     * 保留 tracing 开关判断，未启用追踪时直接跳过。
     * </p>
     */
    private void safeFinishNode(boolean tracing, String traceId, String nodeId,
                                String status, String errorMsg, long duration) {
        if (!tracing) {
            return;
        }
        traceRecordService.finishNode(traceId, nodeId, status, errorMsg, new Date(), duration);
    }

    /**
     * 在流水线执行结束时输出完成日志
     */
    private void logPipelineComplete(StreamChatContext ctx) {
        log.info("流水线执行完成，会话ID：{}，任务ID：{}", ctx.getConversationId(), ctx.getTaskId());
    }

    // ==================== 取消检查 ====================

    /**
     * 检查任务是否已被用户取消，已取消时抛出异常终止后续阶段
     */
    private void checkCancellation(StreamChatContext ctx) {
        if (taskManager.isCancelled(ctx.getTaskId())) {
            log.info("任务已被取消，终止流水线，任务ID：{}", ctx.getTaskId());
            throw new IllegalStateException(CANCEL_MARKER + ": " + ctx.getTaskId());
        }
    }
}
