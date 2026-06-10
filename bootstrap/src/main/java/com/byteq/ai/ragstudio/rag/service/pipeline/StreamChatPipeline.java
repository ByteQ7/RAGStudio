package com.byteq.ai.ragstudio.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
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
import com.byteq.ai.ragstudio.rag.dto.RetrievalContext;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.service.handler.StreamTaskManager;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.MCP_DECISION_PROMPT_PATH;

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
 *   <li><b>MCP 决策（decideMcp）</b>：同步判断是否需要调用 MCP 实时数据工具</li>
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

    private static final String MCP_CALL_PREFIX = "__MCP_CALL__:";

    /**
     * 执行流式对话流水线
     * <p>
     * 流程：记忆加载 → 查询改写 → (有MCP则进入流式决策/无MCP则走KB或兜底)
     * </p>
     */
    public void execute(StreamChatContext ctx) {
        loadMemory(ctx);
        rewriteQuery(ctx);

        if (hasMcpTools()) {
            RetrievalContext retrievalCtx = retrieve(ctx);
            McpDecision decision = decideMcp(ctx);
            if (decision.wantsMcp()) {
                log.info("LLM 决定调用 MCP 工具: {}", decision.toolId());
                executeMcpAndFinalize(ctx, retrievalCtx, decision.toolId());
            } else if (retrievalCtx.hasKb()) {
                log.info("LLM 决定不调用 MCP 工具，走 RAG 回答");
                streamRagResponse(ctx, retrievalCtx);
            } else {
                log.info("LLM 决定不调用 MCP 工具，无知识库，走直接回答");
                streamDirectResponse(ctx);
            }
            return;
        }

        RetrievalContext retrievalCtx = retrieve(ctx);
        if (retrievalCtx.hasKb()) {
            streamRagResponse(ctx, retrievalCtx);
        } else {
            streamDirectResponse(ctx);
        }
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

    private void rewriteQuery(StreamChatContext ctx) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(
                ctx.getQuestion(), ctx.getHistory(), ctx.getKnowledgeBaseIds());
        ctx.setRewriteResult(rewriteResult);
    }

    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieveByKnowledgeBases(ctx.getKnowledgeBaseIds(), ctx.getRewriteResult(), searchProperties.getDefaultTopK());
    }

    // ==================== 流式决策 MCP 调用 ====================

    private boolean hasMcpTools() {
        return CollUtil.isNotEmpty(mcpToolRegistry.listAllExecutors());
    }

    /**
     * 同步决策是否需要调用 MCP 工具（非流式，结果不展示给用户）。
     * 调用 LLM 判断用户问题是否需要实时数据工具，返回决策结果。
     * 若 LLM 响应以 __MCP_CALL__: 开头则提取工具 ID，否则视为不调用。
     *
     * @param ctx 流式对话上下文
     * @return MCP 决策结果
     */
    private McpDecision decideMcp(StreamChatContext ctx) {
        String toolList = buildMcpToolList();

        String decisionPrompt = promptTemplateLoader.render(MCP_DECISION_PROMPT_PATH,
                Map.of("tool_list", toolList));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(decisionPrompt));
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            messages.addAll(ctx.getHistory());
        }
        messages.add(ChatMessage.user(ctx.getQuestion()));

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .thinking(false)
                .temperature(0.1D)
                .maxTokens(300)
                .build();

        String response = llmService.chat(request);
        String trimmed = response.trim();

        if (trimmed.startsWith(MCP_CALL_PREFIX)) {
            int endOfLine = trimmed.indexOf("\n");
            String firstLine = endOfLine > 0 ? trimmed.substring(0, endOfLine).trim() : trimmed;
            String toolId = firstLine.substring(MCP_CALL_PREFIX.length()).trim();
            // 防御性清理：去除 LLM 可能误带的花括号
            toolId = toolId.replace("{", "").replace("}", "");
            return new McpDecision(true, toolId);
        }

        return new McpDecision(false, null);
    }

    /**
     * 构建 MCP 工具列表文本
     * <p>
     * 包含工具名称、描述和完整的参数定义（含类型、必填标记、描述、默认值、枚举值），
     * 确保 LLM 在决策时能充分了解每个工具的能力与参数要求。
     * </p>
     */
    @SuppressWarnings("unchecked")
    private String buildMcpToolList() {
        List<McpToolExecutor> executors = mcpToolRegistry.listAllExecutors();
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

            // 输出完整的参数定义，让 LLM 能准确判断是否可调用
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
     * 第二阶段：执行 MCP 工具后，组装含工具结果的 Prompt，流式输出
     */
    private void executeMcpAndFinalize(StreamChatContext ctx, RetrievalContext retrievalCtx, String toolId) {
        Optional<McpToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            streamRagResponse(ctx, retrievalCtx);
            return;
        }

        McpToolExecutor executor = executorOpt.get();
        Tool tool = executor.getToolDefinition();
        Map<String, Object> params = mcpParameterExtractor.extractParameters(
                ctx.getRewriteResult().rewrittenQuestion(), tool);
        CallToolResult result = executor.execute(params != null ? params : new HashMap<>());

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
                                                          String customPrompt, StreamCallback callback) {
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
                .thinking(false)
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
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }

    // ==================== 内部类 ====================

    private record McpDecision(boolean wantsMcp, String toolId) {
    }
}
