package com.byteq.ai.ragstudio.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.core.agent.AgentContext;
import com.byteq.ai.ragstudio.rag.core.agent.KbRelevanceChecker;
import com.byteq.ai.ragstudio.rag.core.agent.OrchestratorAgent;
import com.byteq.ai.ragstudio.rag.core.agent.QaSubAgent;
import com.byteq.ai.ragstudio.rag.core.agent.ReActPromptBuilder;
import com.byteq.ai.ragstudio.rag.core.agent.ReActResponseParser;
import com.byteq.ai.ragstudio.rag.core.agent.ToolSubAgent;
import com.byteq.ai.ragstudio.rag.core.agent.TitleSubAgent;
import com.byteq.ai.ragstudio.rag.core.mcp.McpParameterExtractor;
import com.byteq.ai.ragstudio.rag.core.prompt.RAGPromptService;
import com.byteq.ai.ragstudio.rag.core.rewrite.QueryRewriteService;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.core.skill.SandboxExecutor;
import okhttp3.OkHttpClient;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.memory.ConversationMemoryService;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrievalEngine;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import com.byteq.ai.ragstudio.rag.service.handler.StreamTaskManager;
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

        // 2. 构建知识库概要文本（名称 + collection + 描述），供 LLM 了解知识库内容
        List<String> finalKbIds = effectiveKbIds;
        final String kbSummaryText;
        final java.util.Map<String, String> collectionToKbId;
        if (CollUtil.isNotEmpty(finalKbIds)) {
            List<KnowledgeBaseDO> selectedKbs = knowledgeBaseMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .in(KnowledgeBaseDO::getId, finalKbIds)
                            .eq(KnowledgeBaseDO::getDeleted, 0));
            // 构建 collectionName → kbId 映射，供 AI 指定 collection 时精确检索
            collectionToKbId = new java.util.HashMap<>();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < selectedKbs.size(); i++) {
                KnowledgeBaseDO kb = selectedKbs.get(i);
                if (StrUtil.isNotBlank(kb.getCollectionName())) {
                    collectionToKbId.put(kb.getCollectionName(), kb.getId());
                }
                sb.append("  ").append(i + 1).append(". ").append(kb.getName());
                if (StrUtil.isNotBlank(kb.getCollectionName())) {
                    sb.append(" [collection: ").append(kb.getCollectionName()).append("]");
                }
                if (StrUtil.isNotBlank(kb.getDescription())) {
                    sb.append(" - ").append(kb.getDescription());
                }
                sb.append("\n");
            }
            kbSummaryText = sb.length() > 0 ? sb.toString().stripTrailing() : "";
        } else {
            kbSummaryText = "";
            collectionToKbId = java.util.Map.of();
        }

        // 3. 构建 Agent 注册中心
        OrchestratorAgent orchestrator = new OrchestratorAgent();

        // Q&A Agent
        QaSubAgent qaSubAgent = traceNode("工具注册", "TOOL_REGISTRY", () -> new QaSubAgent(
                llmService, retrievalEngine, searchProperties,
                mcpToolRegistry, skillLoader, syncHttpClient, sandboxExecutor,
                reactResponseParser, reactPromptBuilder,
                () -> taskManager.isCancelled(ctx.getTaskId()),
                finalKbIds, kbSummaryText, collectionToKbId,
                traceRecordService
        ));
        orchestrator.register(qaSubAgent);

        // Tool Agent（纯工具执行）
        try {
            ToolSubAgent toolAgent = new ToolSubAgent(
                    mcpToolRegistry, skillLoader, syncHttpClient, sandboxExecutor,
                    traceRecordService);
            orchestrator.register(toolAgent);
        } catch (Exception e) {
            log.warn("Tool Agent 注册失败，跳过", e);
        }

        // Title Agent（LLM 调用生成标题）
        try {
            TitleSubAgent titleAgent = new TitleSubAgent(llmService, promptTemplateLoader);
            orchestrator.register(titleAgent);
        } catch (Exception e) {
            log.warn("Title Agent 注册失败，跳过", e);
        }

        checkCancellation(ctx);

        // 4. 构建 AgentContext
        AgentContext agentCtx = new AgentContext(
                ctx.getQuestion(),
                ctx.getHistory(),
                "",        // kbContext：不预检索，Agent 自主调用 rag_search
                kbRelevant,
                List.of(),
                10,
                120_000L,
                ctx.getImageUrls(),
                ctx.getDeepThinkingLevel()
        );

        // 5. 执行 Orchestrator（Task 驱动，SSE 事件透传）
        traceNode("Agent循环", "AGENT_LOOP", () -> {
            orchestrator.run(ctx.getQuestion(), agentCtx, ctx.getCallback());
            return null;
        });

        logPipelineComplete(ctx);
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
