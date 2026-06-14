package com.byteq.ai.ragstudio.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.core.mcp.McpParameterExtractor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.memory.ConversationMemoryService;
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

    /** 链路追踪配置，用于判断是否启用追踪 */
    private final RagTraceProperties traceProperties;

    private static final String MCP_CALL_PREFIX = "__MCP_CALL__:";

    /** 取消异常前缀标识，用于在 catch 中区分用户取消 vs 其他 IllegalStateException */
    private static final String CANCEL_MARKER = "任务已被用户取消";

    /** 线程安全的 ObjectMapper 单例，避免每次调用都创建新实例 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 执行流式对话流水线
     * <p>
     * 流程：记忆加载 → 查询改写（含 MCP 决策）→ 检索 → 回答
     * 同步阶段通过 traceNode 自动记录执行耗时到链路追踪系统。
     * 流式阶段仅记录开始日志（实际耗时由底层 StreamSpan 和 run-level trace 记录）。
     * </p>
     */
    public void execute(StreamChatContext ctx) {
        try {
            doExecute(ctx);
        } catch (IllegalStateException e) {
            // 仅静默处理用户取消：其他 IllegalStateException 仍需正常传播
            if (e.getMessage() != null && e.getMessage().startsWith(CANCEL_MARKER)) {
                log.info("流水线因任务取消而终止，任务ID：{}", ctx.getTaskId());
                return;
            }
            throw e;
        }
    }

    private void doExecute(StreamChatContext ctx) {
        traceNode("记忆加载", "MEMORY", () -> {
            loadMemory(ctx);
            return null;
        });
        checkCancellation(ctx);

        traceNode("查询改写", "REWRITE", () -> {
            rewriteQuery(ctx);
            return null;
        });
        checkCancellation(ctx);

        String mcpToolId = ctx.getMcpToolId();
        if (StrUtil.isNotBlank(mcpToolId)) {
            log.info("LLM 决定调用 MCP 工具: {}", mcpToolId);
            RetrievalContext retrievalCtx = traceNode("知识检索", "RETRIEVE", () -> retrieve(ctx));
            checkCancellation(ctx);
            traceNode("MCP工具执行", "MCP", () -> {
                executeMcpAndFinalize(ctx, retrievalCtx, mcpToolId);
                return null;
            });
            logPipelineComplete(ctx);
            return;
        }

        RetrievalContext retrievalCtx = traceNode("知识检索", "RETRIEVE", () -> retrieve(ctx));
        checkCancellation(ctx);

        // 流式阶段不包装 traceNode：llmService.streamChat() 异步返回，
        // 记录的 duration 仅为发起耗时而非实际流式耗时，且状态在流完成前就标记 SUCCESS。
        // 实际流式耗时由底层 StreamSpan 和 StreamChatTraceRunner 的 run-level trace 准确记录。
        if (retrievalCtx.hasKb()) {
            log.info("流水线阶段 [流式回答] 开始");
            streamRagResponse(ctx, retrievalCtx);
        } else {
            log.info("流水线阶段 [直接回答] 开始");
            streamDirectResponse(ctx);
        }
        logPipelineComplete(ctx);
    }

    // ==================== 流水线阶段 ====================

    private void loadMemory(StreamChatContext ctx) {
        List<ChatMessage> history = memoryService.loadAndAppend(
                ctx.getConversationId(),
                ctx.getUserId(),
                ChatMessage.user(ctx.getQuestion())
        );
        ctx.setHistory(history);
    }

    /**
     * 查询改写（当 MCP 工具存在时，合并 MCP 决策到同一次 LLM 调用）
     * <p>
     * 无 MCP 工具时：直接调用改写服务。
     * 有 MCP 工具时：将改写指令和 MCP 决策指令合并为一条 Prompt，
     * 让 LLM 一次性返回改写结果和 MCP 工具选择，省去一次 LLM 调用。
     * 仅在合并响应解析失败时，才回退调用改写服务作为兜底。
     * </p>
     */
    private void rewriteQuery(StreamChatContext ctx) {
        List<McpToolExecutor> executors = mcpToolRegistry.listAllExecutors();
        if (CollUtil.isEmpty(executors)) {
            // 无 MCP 工具，走原有改写流程
            RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(
                    ctx.getQuestion(), ctx.getHistory(), ctx.getKnowledgeBaseIds());
            if (rewriteResult == null) {
                rewriteResult = new RewriteResult(ctx.getQuestion(), List.of(ctx.getQuestion()));
            }
            ctx.setRewriteResult(rewriteResult);
            return;
        }

        // 有 MCP 工具：合并改写 + MCP 决策为一次 LLM 调用
        String toolList = buildMcpToolList(executors);
        String rewritePrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);

        // 在改写 Prompt 末尾追加 MCP 决策指令
        String combinedSystemPrompt = rewritePrompt + "\n\n"
                + "## 实时数据工具判断\n\n"
                + "以下是一些可用的实时数据工具：\n\n"
                + toolList + "\n\n"
                + "如果用户问题需要通过以上某个工具获取实时数据才能回答，"
                + "请在输出最开头单独一行写上 " + MCP_CALL_PREFIX + "工具ID，然后换行，再输出改写结果 JSON。\n"
                + "如果不需要使用任何工具，直接输出改写结果 JSON 即可。";

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(combinedSystemPrompt));
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            messages.addAll(ctx.getHistory());
        }
        messages.add(ChatMessage.user(ctx.getQuestion()));

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .thinking(false)
                .temperature(0.1D)
                .maxTokens(500)
                .build();

        String response = llmService.chat(request);
        if (StrUtil.isBlank(response)) {
            log.warn("合并改写 LLM 返回空响应，回退到独立改写服务");
            ctx.setRewriteResult(queryRewriteService.rewriteWithSplit(
                    ctx.getQuestion(), ctx.getHistory(), ctx.getKnowledgeBaseIds()));
            return;
        }
        String trimmed = response.trim();

        // 解析 MCP 调用决策（兼容无换行的单行响应）
        String mcpToolId = null;
        String rewriteRaw = trimmed;
        if (trimmed.startsWith(MCP_CALL_PREFIX)) {
            int endOfLine = trimmed.indexOf("\n");
            String firstLine = endOfLine > 0 ? trimmed.substring(0, endOfLine).trim() : trimmed;
            mcpToolId = firstLine.substring(MCP_CALL_PREFIX.length()).trim();
            mcpToolId = mcpToolId.replace("{", "").replace("}", "");
            rewriteRaw = endOfLine > 0 ? trimmed.substring(endOfLine).trim() : "";
        }

        // 尝试从 LLM 响应中解析改写 JSON；仅在解析失败时才回退调用改写服务（懒加载兜底）
        RewriteResult parsed = tryParseRewriteResult(rewriteRaw, null);
        if (parsed == null) {
            log.info("合并改写响应解析失败，回退到独立改写服务");
            parsed = queryRewriteService.rewriteWithSplit(
                    ctx.getQuestion(), ctx.getHistory(), ctx.getKnowledgeBaseIds());
        }
        // 终极兜底：如果改写服务也返回 null，使用原始问题防止下游 NPE
        if (parsed == null) {
            log.warn("改写服务兜底也返回 null，使用原始问题");
            parsed = new RewriteResult(ctx.getQuestion(), List.of(ctx.getQuestion()));
        }

        ctx.setRewriteResult(parsed);
        ctx.setMcpToolId(mcpToolId);

        if (StrUtil.isNotBlank(mcpToolId)) {
            log.info("改写阶段合并 MCP 决策：调用工具 {}", mcpToolId);
        }
    }

    /**
     * 尝试从 LLM 响应中解析改写结果
     *
     * @param raw      LLM 原始响应文本
     * @param fallback 解析失败时的兜底结果，传 null 表示由调用方决定兜底策略
     * @return 解析成功返回 RewriteResult，失败返回 fallback
     */
    private RewriteResult tryParseRewriteResult(String raw, RewriteResult fallback) {
        if (StrUtil.isBlank(raw)) {
            return fallback;
        }
        try {
            String cleaned = com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner.stripMarkdownCodeFence(raw);
            cleaned = com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner.extractJson(cleaned);

            JsonNode json = OBJECT_MAPPER.readTree(cleaned);

            // 安全读取 rewrite 字段，防止 JSON null 值被 Jackson 转为字符串 "null"
            String rewritten = extractTextOrNull(json, "rewrite");
            if (StrUtil.isBlank(rewritten)) {
                rewritten = extractTextOrNull(json, "rewritten_question");
            }

            List<String> subQuestions = new ArrayList<>();
            if (json.has("sub_questions") && json.get("sub_questions").isArray()) {
                for (var item : json.get("sub_questions")) {
                    subQuestions.add(item.asText());
                }
            }

            if (StrUtil.isNotBlank(rewritten)) {
                if (subQuestions.isEmpty()) {
                    subQuestions = List.of(rewritten);
                }
                return new RewriteResult(rewritten, subQuestions);
            }
        } catch (Exception e) {
            log.warn("解析合并后的改写结果失败", e);
        }
        return fallback;
    }

    /**
     * 从 JsonNode 中安全提取文本值，正确处理 JSON null
     */
    private String extractTextOrNull(JsonNode json, String fieldName) {
        if (!json.has(fieldName)) {
            return null;
        }
        JsonNode node = json.get(fieldName);
        return node.isNull() ? null : node.asText();
    }

    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieveByKnowledgeBases(
                ctx.getKnowledgeBaseIds(), ctx.getRewriteResult(), searchProperties.getDefaultTopK());
    }

    // ==================== MCP 调用 ====================

    /**
     * 构建 MCP 工具列表文本
     * <p>
     * 包含工具名称、描述和完整的参数定义（含类型、必填标记、描述、默认值、枚举值），
     * 确保 LLM 在决策时能充分了解每个工具的能力与参数要求。
     * </p>
     *
     * @param executors 已注册的 MCP 工具执行器列表（由调用方提供，避免重复查询注册表）
     */
    @SuppressWarnings("unchecked")
    private String buildMcpToolList(List<McpToolExecutor> executors) {
        if (CollUtil.isEmpty(executors)) {
            return "当前无可用的实时数据工具。";
        }
        StringBuilder sb = new StringBuilder();
        for (McpToolExecutor executor : executors) {
            Tool tool = executor.getToolDefinition();
            sb.append("### 工具: ").append(tool.name()).append("\n");
            if (StrUtil.isNotBlank(tool.description())) {
                sb.append("描述: ").append(tool.description()).append("\n");
            }

            if (tool.inputSchema() != null && tool.inputSchema().properties() != null
                    && !tool.inputSchema().properties().isEmpty()) {
                List<String> requiredList = tool.inputSchema().required() != null
                        ? tool.inputSchema().required() : List.of();
                sb.append("参数:\n");
                for (Map.Entry<String, Object> entry : tool.inputSchema().properties().entrySet()) {
                    String paramName = entry.getKey();
                    Map<String, Object> propDef = (Map<String, Object>) entry.getValue();

                    String type = propDef.getOrDefault("type", "string").toString();
                    boolean required = requiredList.contains(paramName);
                    String description = propDef.getOrDefault("description", "").toString();

                    sb.append("  - ").append(paramName)
                            .append(" (").append(type)
                            .append(required ? ", 必填" : ", 可选").append(")");
                    if (StrUtil.isNotBlank(description)) {
                        sb.append(": ").append(description);
                    }
                    Object defaultValue = propDef.get("default");
                    if (defaultValue != null) {
                        sb.append(" [默认值: ").append(defaultValue).append("]");
                    }
                    Object enumValues = propDef.get("enum");
                    if (enumValues instanceof List<?> enumList && !enumList.isEmpty()) {
                        String enumStr = enumList.stream().map(Object::toString)
                                .collect(java.util.stream.Collectors.joining(", "));
                        sb.append(" [可选值: ").append(enumStr).append("]");
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("参数: 无\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 执行 MCP 工具后，组装含工具结果的 Prompt，流式输出
     * <p>
     * 如果 MCP 工具执行失败（网络超时、协议错误等），降级为普通 RAG 回答，
     * 利用已检索到的知识库上下文，避免用户看到通用错误。
     * </p>
     */
    private void executeMcpAndFinalize(StreamChatContext ctx, RetrievalContext retrievalCtx, String toolId) {
        Optional<McpToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}，降级为普通 RAG 回答", toolId);
            streamRagResponse(ctx, retrievalCtx);
            return;
        }

        McpToolExecutor executor = executorOpt.get();
        Tool tool = executor.getToolDefinition();
        CallToolResult result;
        try {
            Map<String, Object> params = mcpParameterExtractor.extractParameters(
                    ctx.getRewriteResult().rewrittenQuestion(), tool);
            result = executor.execute(params != null ? params : new HashMap<>());
        } catch (Exception e) {
            log.warn("MCP 工具 {} 执行失败，降级为普通 RAG 回答: {}", toolId, e.getMessage());
            streamRagResponse(ctx, retrievalCtx);
            return;
        }

        String mcpContext = formatMcpToolResult(toolId, result);

        RetrievalContext enrichedCtx = RetrievalContext.builder()
                .kbContext(retrievalCtx.getKbContext())
                .mcpContext(mcpContext)
                .build();

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                enrichedCtx,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    private String formatMcpToolResult(String toolId, CallToolResult result) {
        if (result == null || CollUtil.isEmpty(result.content())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### 工具 ").append(toolId).append(" 返回结果\n\n");
        for (var content : result.content()) {
            if (content instanceof TextContent textContent) {
                sb.append(textContent.text()).append("\n");
            } else if (content instanceof ImageContent imageContent) {
                String mimeType = imageContent.mimeType() != null ? imageContent.mimeType() : "image/png";
                sb.append("![生成的图片](data:").append(mimeType)
                        .append(";base64,").append(imageContent.data()).append(")\n");
            }
        }
        return sb.toString();
    }

    // ==================== 流式输出 ====================

    private void streamDirectResponse(StreamChatContext ctx) {
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                null,
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, boolean deepThinking,
                                                          StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.4D)
                .thinking(deepThinking)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()
        );

        // 关键调试日志：记录发送给 LLM 的消息结构，帮助排查"检索到但回答没有"的问题
        log.info("LLM 请求概览 - 消息数: {}, KB上下文: {} ({}字符), MCP上下文: {}, 历史消息数: {}, 子问题数: {}",
                messages.size(),
                ctx.hasKb() ? "有" : "无",
                ctx.getKbContext() != null ? ctx.getKbContext().length() : 0,
                ctx.hasMcp() ? "有" : "无",
                history.size(),
                rewriteResult.subQuestions() != null ? rewriteResult.subQuestions().size() : 0);
        if (log.isDebugEnabled()) {
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                log.debug("  消息[{}] role={}, 内容长度={}, 前200字符: {}",
                        i, msg.getRole(),
                        msg.getContent() != null ? msg.getContent().length() : 0,
                        msg.getContent() != null
                                ? msg.getContent().substring(0, Math.min(200, msg.getContent().length()))
                                : "null");
            }
        }

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
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
            log.error("流水线阶段 [{}] 失败，耗时 {}ms", name, duration, ex);
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
