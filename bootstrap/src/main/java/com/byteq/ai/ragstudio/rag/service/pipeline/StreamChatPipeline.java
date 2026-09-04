package com.byteq.ai.ragstudio.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.framework.trace.TraceStatus;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.core.agent.AgentContext;
import com.byteq.ai.ragstudio.rag.core.agent.AgentScopeReActExecutor;
import com.byteq.ai.ragstudio.rag.core.agent.KbEmbeddingSelector;
import com.byteq.ai.ragstudio.rag.core.rewrite.QueryRewriteService;
import com.byteq.ai.ragstudio.rag.core.rewrite.RewriteResult;
import com.byteq.ai.ragstudio.rag.core.retrieve.EntityIdQueryDetector;
import com.byteq.ai.ragstudio.rag.core.skill.SandboxExecutor;
import com.byteq.ai.ragstudio.rag.core.memory.ConversationMemoryService;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import com.byteq.ai.ragstudio.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

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

    /** 对话记忆服务，负责加载和管理对话历史 */
    private final ConversationMemoryService memoryService;

    /** 对话分组管理服务，负责组内新会话归组与分组指令解析 */
    private final com.byteq.ai.ragstudio.rag.service.ConversationGroupManager conversationGroupManager;

    /** 查询改写服务，负责对用户问题进行语义改写和拆分 */
    private final QueryRewriteService queryRewriteService;

    /** 流式任务管理器，负责管理流式对话任务的取消和状态跟踪 */
    private final StreamTaskManager taskManager;

    /** 链路追踪记录服务，用于记录各阶段的执行耗时 */
    private final RagTraceRecordService traceRecordService;

    /** AgentScope ReActAgent 执行器（替代自研 JSON ReACT 循环） */
    private final AgentScopeReActExecutor agentscopeExecutor;

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

    @org.springframework.beans.factory.annotation.Value("${rag.skills.sandbox.enabled:true}")
    private boolean sandboxEnabled;

    @org.springframework.beans.factory.annotation.Value("${rag.skills.allowed-commands:}")
    private String allowedCommands;

    @org.springframework.beans.factory.annotation.Value("${rag.agent.max-iterations:10}")
    private int agentMaxIterations;

    @org.springframework.beans.factory.annotation.Value("${rag.agent.timeout-ms:120000}")
    private long agentTimeoutMs;

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

    /** 知识库语义选择器（嵌入模型，多模态），按用户问题自动选择相关知识库 */
    private final KbEmbeddingSelector kbEmbeddingSelector;

    /** HTTP 模型工厂，用于将 S3 图片 URL 转为 data URI（多模态选库用） */
    private final com.byteq.ai.ragstudio.infra.http.HttpModelFactory httpModelFactory;

    /** 知识库 DAO，用于查询知识库名称和描述 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /** Agent 模式标识 */


    /** 分组指令注入前缀（元宝式分组：组内对话自动延续该指令风格） */
    private static final String GROUP_INSTRUCTION_PREFIX =
            "【对话分组指令】请在本次会话的所有回复中遵循以下用户设定：\n";

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
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(CANCEL_MARKER)) {
                log.info("流水线因任务取消而终止，任务ID：{}", taskId);
                return;
            }
            throw e;
        }
    }

    private void doExecute(StreamChatContext ctx) {
        doExecuteAgent(ctx);
    }

    // ==================== Agent 模式 ====================

    /**
     * Agent 模式执行流程：记忆加载 → 查询改写 → 相关性判断+KB过滤 → AgentScope ReActAgent
     */
    private void doExecuteAgent(StreamChatContext ctx) {
        String userOriginalQuestion = ctx.getQuestion();

        traceNode("记忆加载", "MEMORY", () -> {
            loadMemory(ctx);
            return null;
        });
        checkCancellation(ctx);

        // 含强实体 ID 的查询（税号/单号/编码等，含"91330108MA1K2L3M4N？"、"帮我查下xxx"等形态）：
        // ① 跳过查询改写——改写模型可能篡改 ID（曾出现 91330108 → 913330108 幻觉）；
        // ② 跳过知识库语义选择——随机串与 KB 描述向量相似度趋近 0，必然误杀相关库。
        // 检索阶段对 ID 查询只走关键词精确匹配（见 RrfHybridChannel）。
        boolean entityIdQuery = EntityIdQueryDetector.containsStrongEntityId(userOriginalQuestion);

        // 1. 查询改写（用于检索阶段，不影响原始问题保留给重排序）
        RewriteResult rewriteResult = entityIdQuery
                ? new RewriteResult(userOriginalQuestion, List.of(userOriginalQuestion))
                : traceNode("查询改写", "REWRITE", () ->
                        queryRewriteService.rewriteWithSplit(userOriginalQuestion, ctx.getHistory(), ctx.getKnowledgeBaseIds()));
        checkCancellation(ctx);
        String rewrittenQuestion = rewriteResult.rewrittenQuestion();
        if (StrUtil.isBlank(rewrittenQuestion)) rewrittenQuestion = userOriginalQuestion;
        final String effectiveRewrittenQuestion = rewrittenQuestion;

        // 2. 加载知识库列表（名称 + collection + 描述），供语义选择和后续 LLM 上下文使用
        List<String> effectiveKbIds = ctx.getKnowledgeBaseIds();
        boolean kbRelevant = false;
        java.util.Map<String, KnowledgeBaseDO> kbMapById = new java.util.HashMap<>();
        if (CollUtil.isNotEmpty(ctx.getKnowledgeBaseIds())) {
            List<KnowledgeBaseDO> allKbs = knowledgeBaseMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                            .in(KnowledgeBaseDO::getId, ctx.getKnowledgeBaseIds()));
            for (KnowledgeBaseDO kb : allKbs) {
                kbMapById.put(kb.getId(), kb);
            }

            // 用户问题附带图片时，转为 data URI 供多模态嵌入选库
            List<String> imageDataUris = new ArrayList<>();
            for (String url : ctx.getImageUrls()) {
                String dataUri = httpModelFactory.resolveImageDataUri(url);
                if (StrUtil.isNotBlank(dataUri)) {
                    imageDataUris.add(dataUri);
                }
            }

            List<KbEmbeddingSelector.KbInfo> kbInfos = ctx.getKnowledgeBaseIds().stream()
                    .map(kbMapById::get)
                    .filter(Objects::nonNull)
                    .map(kb -> new KbEmbeddingSelector.KbInfo(
                            kb.getId(), kb.getName(), kb.getDescription(), kb.getCollectionName()))
                    .toList();

            // 使用嵌入模型（多模态）按语义相似度选择相关知识库，高于阈值的全部命中。
            // 单知识库 + 无图片 + 轻量文本相关性命中时跳过语义选择：
            // 省一次批量 Embedding 远程调用（显然相关场景选库收益≈0）；
            // 文本相关性未命中则仍走语义选库，保留"无关问题不检索"的过滤能力
            boolean singleKbFastPath = ctx.getKnowledgeBaseIds().size() == 1
                    && CollUtil.isEmpty(ctx.getImageUrls())
                    && kbTextLikelyRelated(userOriginalQuestion, kbInfos.stream()
                            .filter(kb -> kb.id().equals(ctx.getKnowledgeBaseIds().get(0)))
                            .findFirst().orElse(null));
            KbEmbeddingSelector.SelectionResult selection;
            if (entityIdQuery) {
                // 纯实体 ID：随机串无语义可比性，直接检索全部已选知识库
                selection = KbEmbeddingSelector.SelectionResult.relevant("纯实体ID查询，跳过语义选库，直接检索全部已选知识库",
                        kbInfos.stream()
                                .map(kb -> new KbEmbeddingSelector.SelectedKb(kb.id(), kb.name(), 1.0))
                                .toList());
                log.info("纯实体ID查询，跳过语义选库: question={}, kbCount={}", userOriginalQuestion, kbInfos.size());
            } else if (singleKbFastPath) {
                String kbId = ctx.getKnowledgeBaseIds().get(0);
                KbEmbeddingSelector.KbInfo sole = kbInfos.stream()
                        .filter(kb -> kb.id().equals(kbId))
                        .findFirst()
                        .orElse(null);
                if (sole != null) {
                    selection = KbEmbeddingSelector.SelectionResult.relevant("单知识库直接检索（跳过语义选库）",
                            List.of(new KbEmbeddingSelector.SelectedKb(sole.id(), sole.name(), 1.0)));
                    log.info("单知识库直接检索，跳过语义选库: kbId={}", kbId);
                } else {
                    selection = traceNode("知识库选择", "KB_SELECT", () ->
                            kbEmbeddingSelector.select(userOriginalQuestion, imageDataUris, kbInfos));
                }
            } else {
                selection = traceNode("知识库选择", "KB_SELECT", () ->
                        kbEmbeddingSelector.select(userOriginalQuestion, imageDataUris, kbInfos));
            }
            checkCancellation(ctx);

            kbRelevant = selection.relevant();
            if (selection.hasSpecificCollections()) {
                effectiveKbIds = selection.selected().stream()
                        .map(KbEmbeddingSelector.SelectedKb::id)
                        .toList();
            } else {
                effectiveKbIds = List.of();
            }
            log.info("知识库语义选择: relevant={}, reasoning='{}', 最终{}个相关KB",
                    kbRelevant, selection.reasoning(), effectiveKbIds.size());
        } else {
            log.info("未选择知识库，跳过检索");
        }

        // 3. 构建知识库概要文本（仅使用过滤后相关的 KB）
        if (CollUtil.isEmpty(effectiveKbIds)) {
            kbRelevant = false;
        }
        List<String> finalKbIds = effectiveKbIds;
        final String kbSummaryText;
        if (CollUtil.isNotEmpty(finalKbIds)) {
            StringBuilder sb = new StringBuilder();
            int idx = 0;
            for (String kbId : finalKbIds) {
                KnowledgeBaseDO kb = kbMapById.get(kbId);
                if (kb == null) continue;
                idx++;
                sb.append("  ").append(idx).append(". ").append(kb.getName());
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
        }

        // 4. 构建 AgentContext（迭代次数与总超时来自 rag.agent.* 配置，见 application.yaml）
        AgentContext agentCtx = new AgentContext(
                userOriginalQuestion,
                ctx.getHistory(),
                "",
                kbRelevant,
                List.of(),
                agentMaxIterations,
                agentTimeoutMs,
                ctx.getImageUrls(),
                ctx.getDeepThinkingLevel(),
                ctx.getConversationId(),
                ctx.getUserId(),
                finalKbIds,
                kbSummaryText,
                effectiveRewrittenQuestion,
                // 复用改写阶段拆分的子问题：多问句查询按子问题并行召回，避免重复支付改写成本
                rewriteResult.subQuestions()
        );

        // 5. 执行 AgentScope ReActAgent（Task 驱动，SSE 事件透传）
        // join 等待 Agent 事件流完全结束：trace 节点记录完整 Agent 时长，
        // 同时让限流 permit / 会话并发锁自然持有到流式回答真正结束
        traceNode("Agent循环", "AGENT_LOOP", () -> {
            agentscopeExecutor.run(agentCtx, ctx.getTaskId(), sandboxExecutor,
                    sandboxEnabled, parseAllowedCommandPrefixes(), ctx.getCallback()).join();
            return null;
        });

        logPipelineComplete(ctx);
    }

    /** 解析 allowed-commands 白名单：逗号分隔的命令前缀列表 */
    private List<String> parseAllowedCommandPrefixes() {
        if (StrUtil.isBlank(allowedCommands)) {
            return List.of();
        }
        return java.util.Arrays.stream(allowedCommands.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    // 加载对话历史记忆并将当前用户问题（含图片 URL）追加到上下文中；
    // 若请求携带分组 ID（组内新建对话），新会话落库后自动归组；
    // 会话所属分组配置了专属指令时，以 SYSTEM 消息注入历史头部
    // （buildSystemPrompt 会将历史中的 SYSTEM 消息合并进系统提示词，与会话摘要 SYSTEM 消息同一机制）
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

        // 组内新建对话：首条消息创建会话后归组（已有会话行 group_id 非空时条件更新自动跳过）
        if (StrUtil.isNotBlank(ctx.getGroupId())) {
            conversationGroupManager.assignGroupToConversation(ctx.getConversationId(), ctx.getUserId(), ctx.getGroupId());
        }

        // 分组专属指令注入：按会话行 group_id 解析（移动分组后即时生效），静默失败不影响对话
        try {
            String groupInstruction = conversationGroupManager.resolveGroupInstruction(ctx.getConversationId(), ctx.getUserId());
            if (StrUtil.isNotBlank(groupInstruction)) {
                List<ChatMessage> withInstruction = new ArrayList<>(history.size() + 1);
                withInstruction.add(ChatMessage.system(GROUP_INSTRUCTION_PREFIX + groupInstruction.trim()));
                withInstruction.addAll(history);
                history = withInstruction;
            }
        } catch (Exception e) {
            log.warn("分组指令注入失败，跳过 - conversationId: {}", ctx.getConversationId(), e);
        }

        ctx.setHistory(history);
    }

    /**
     * 轻量文本相关性判断（单知识库快路径用）：
     * 问题与知识库名称/描述存在双向包含或 2-gram 重叠时判定为相关。
     * <ul>
     *   <li>相关 → 跳过语义选库（省一次 Embedding 远程调用）</li>
     *   <li>无法判断（库无名称描述/问题过短）→ 放行快路径（单库场景选库收益本来就低）</li>
     *   <li>不相关 → 仍走语义选库，保留"无关问题不检索"的过滤能力</li>
     * </ul>
     */
    private boolean kbTextLikelyRelated(String question, KbEmbeddingSelector.KbInfo kb) {
        if (StrUtil.isBlank(question) || kb == null) {
            return false;
        }
        String q = question.trim().toLowerCase().replaceAll("[\\s?？。，,！!；;、：:]", "");
        if (q.length() < 2) {
            return true;
        }
        String kbText = StrUtil.nullToEmpty(kb.name()).toLowerCase()
                + StrUtil.nullToEmpty(kb.description()).toLowerCase();
        if (kbText.isBlank()) {
            return true;
        }
        if (kbText.contains(q)) {
            return true;
        }
        // 2-gram 重叠：中文问题与库描述无需分词即可捕捉"报销/年假/制度"等业务词
        for (int i = 0; i + 2 <= q.length(); i++) {
            if (kbText.contains(q.substring(i, i + 2))) {
                return true;
            }
        }
        return false;
    }

    // ==================== 链路追踪 ====================

    private static final String TRACE_STATUS_RUNNING = TraceStatus.RUNNING.name();
    private static final String TRACE_STATUS_SUCCESS = TraceStatus.SUCCESS.name();
    private static final String TRACE_STATUS_ERROR = TraceStatus.ERROR.name();

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
