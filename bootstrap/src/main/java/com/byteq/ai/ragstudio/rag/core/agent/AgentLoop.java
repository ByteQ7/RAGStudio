package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReACT Agent 循环引擎
 * <p>
 * 核心循环流程：
 * <ol>
 *   <li>构建初始消息列表（System Prompt + History + User Question）</li>
 *   <li>进入循环：同步调用 LLM → 解析响应 → 推送 Step → 判断动作</li>
 *   <li>TOOL_CALL：执行工具 → 格式化 Observation → 追加到消息列表 → 继续循环</li>
 *   <li>FINISH：流式输出最终回答 → 结束</li>
 *   <li>ERROR / 超时 / 超迭代：降级处理 → 推送错误 → 结束</li>
 * </ol>
 * <p>
 * 中间推理步骤通过 {@link StreamCallback#onAgentStep(Object)} 实时推送至前端。
 * 最终回答通过 {@link StreamCallback#onContent(String)} 逐块流式输出。
 */
@Slf4j
public class AgentLoop {

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ReActResponseParser responseParser;
    private final ReActPromptBuilder promptBuilder;

    /** 最终回答流式输出时每次推送的字符数 */
    private static final int FINAL_ANSWER_CHUNK_SIZE = 5;

    /** 取消异常标记 */
    private static final String CANCEL_MARKER = "任务已被用户取消";

    /** JSON 序列化 */
    private static final Gson GSON = new Gson();

    /** 在 onComplete 之前触发的回调（用于引用溯源等） */
    private Runnable beforeCompleteCallback;

    public AgentLoop(LLMService llmService,
                     ToolRegistry toolRegistry,
                     ReActResponseParser responseParser,
                     ReActPromptBuilder promptBuilder) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.responseParser = responseParser;
        this.promptBuilder = promptBuilder;
    }

    /** 设置在 onComplete 之前触发的回调 */
    public void setBeforeCompleteCallback(Runnable callback) {
        this.beforeCompleteCallback = callback;
    }

    /**
     * 执行 ReACT Agent 循环
     *
     * @param ctx      Agent 循环上下文（包含问题、历史、KB上下文、工具等）
     * @param callback 流式回调（接收 agent_step 和最终回答内容）
     */
    public void run(AgentContext ctx, StreamCallback callback) {
        ctx.markStart();
        log.info("Agent 循环开始 - question='{}', tools={}, kbRelevant={}, maxIterations={}",
                truncate(ctx.getQuestion(), 50), ctx.getTools().size(),
                ctx.isKbRelevant(), ctx.getMaxIterations());

        try {
            // 1. 构建初始消息列表
            List<ChatMessage> messages = buildInitialMessages(ctx);
            ctx.getMessages().addAll(messages);

            // 2. 循环迭代
            for (int iteration = 0; iteration < ctx.getMaxIterations(); iteration++) {
                // 超时检查
                if (ctx.isTimedOut()) {
                    log.warn("Agent 循环超时 ({}ms)，强制终止", ctx.getTimeoutMs());
                    AgentStep timeoutStep = AgentStep.error(iteration, "",
                            "推理超时（已超过" + ctx.getTimeoutMs() / 1000 + "秒），请简化问题重试");
                    pushStep(timeoutStep, callback);
                    pushStepsComplete(ctx, callback);
                    streamFallbackAnswer("抱歉，推理过程超时了，请尝试简化您的问题再问一次。", callback);
                    return;
                }

                log.info("Agent 迭代 {}/{} - 消息数: {}", iteration, ctx.getMaxIterations(),
                        ctx.getMessages().size());

                // 2a. 同步调用 LLM
                String llmResponse;
                try {
                    llmResponse = llmService.chat(ChatRequest.builder()
                            .messages(new ArrayList<>(ctx.getMessages()))
                            .temperature(0.0)   // 最低温度，最大程度保证格式遵守
                            .thinking(false)
                            .build());
                } catch (Exception e) {
                    log.error("Agent 迭代 {} LLM 调用失败: {}", iteration, e.getMessage());
                    AgentStep errorStep = AgentStep.error(iteration, "",
                            "模型调用失败: " + e.getMessage());
                    pushStep(errorStep, callback);
                    pushStepsComplete(ctx, callback);
                    streamFallbackAnswer("抱歉，模型服务暂时不可用，请稍后重试。", callback);
                    return;
                }

                if (StrUtil.isBlank(llmResponse)) {
                    log.warn("Agent 迭代 {} LLM 返回空响应，自动重试一次", iteration);
                    try {
                        llmResponse = llmService.chat(ChatRequest.builder()
                                .messages(new ArrayList<>(ctx.getMessages()))
                                .temperature(0.1)
                                .thinking(false)
                                .build());
                    } catch (Exception e2) {
                        log.warn("Agent 迭代 {} 重试也失败: {}", iteration, e2.getMessage());
                    }
                }

                if (StrUtil.isBlank(llmResponse)) {
                    log.warn("Agent 迭代 {} 重试后仍为空，终止", iteration);
                    AgentStep emptyStep = AgentStep.error(iteration, "", "模型返回空响应");
                    pushStep(emptyStep, callback);
                    pushStepsComplete(ctx, callback);
                    streamFallbackAnswer("抱歉，没有获取到有效回答，请重试。", callback);
                    return;
                }

                // 2b. 解析响应
                AgentStep step = responseParser.parse(llmResponse, iteration);

                // 格式校正：初次迭代 LLM 未使用 ReACT 格式时重试一次
                if (iteration == 0 && !llmResponse.contains("Action:") && !llmResponse.contains("Action：")) {
                    log.warn("Agent 初次迭代 LLM 未使用 ReACT 格式，注入纠正提示后重试");
                    ctx.addMessage(ChatMessage.assistant(llmResponse));
                    ctx.addMessage(ChatMessage.system(
                            "你刚才的输出没有遵循 ReACT 格式。请重新输出，"
                            + "确保包含 Thought: 和 Action: 字段。"
                            + "即使你认为不需要调用工具，也必须输出：\n"
                            + "Thought: ...\nAction: FINISH\nFinal Answer: ..."));
                    try {
                        String retryResponse = llmService.chat(ChatRequest.builder()
                                .messages(new ArrayList<>(ctx.getMessages()))
                                .temperature(0.0)
                                .thinking(false)
                                .build());
                        if (StrUtil.isNotBlank(retryResponse)) {
                            llmResponse = retryResponse;
                            step = responseParser.parse(llmResponse, iteration);
                        }
                    } catch (Exception e2) {
                        log.warn("格式校正重试也失败: {}", e2.getMessage());
                    }
                }

                ctx.addStep(step);

                // 追加 LLM 响应到消息列表
                ctx.addMessage(ChatMessage.assistant(llmResponse));

                // 2c. 如有 Plan，注入计划到上下文（后续迭代可参照执行）
                String plan = step.getPlan();
                if (StrUtil.isNotBlank(plan)) {
                    ctx.addMessage(ChatMessage.system("【当前执行计划】\n" + plan
                            + "\n\n请严格按照此计划逐条执行，每完成一步检查下一步。"));
                }

                // 2d. 推送步骤到前端
                pushStep(step, callback);

                // 2e. 根据动作分支
                if (step.getAction() == AgentAction.FINISH) {
                    log.info("Agent 完成 - iteration={}, finalAnswerLength={}",
                            iteration,
                            step.getFinalAnswer() != null ? step.getFinalAnswer().length() : 0);
                    pushStepsComplete(ctx, callback);
                    streamFinalAnswer(step.getFinalAnswer(), callback);
                    return;    
                }

                if (step.getAction() == AgentAction.TOOL_CALL) {
                    // 2e. 执行工具（失败时自动重试一次）
                    log.info("Agent 调用工具: {}, params={}", step.getToolName(), step.getToolInput());
                    ToolResult result = toolRegistry.execute(step.getToolName(), step.getToolInput());
                    if (!result.isSuccess()) {
                        log.warn("工具 {} 执行失败，自动重试一次: {}", step.getToolName(), result.getContent());
                        result = toolRegistry.execute(step.getToolName(), step.getToolInput());
                    }
                    step.setObservation(result.toObservation());
                    step.setDurationMs(result.getDurationMs());

                    // 推送 Observation 更新
                    pushStep(step, callback);

                    // 将 Observation 追加到消息列表
                    ctx.addMessage(ChatMessage.user(result.toObservation()));
                    continue;
                }

                // ERROR 或其他未知状态 → 终止
                log.warn("Agent 迭代 {} 返回 ERROR 或未知动作: {}", iteration, step.getAction());
                pushStepsComplete(ctx, callback);
                streamFallbackAnswer(
                        "抱歉，推理过程出现异常，请重新描述您的问题。", callback);
                return;
            }

            // 达到最大迭代次数
            log.warn("Agent 达到最大迭代次数 {}，强制终止", ctx.getMaxIterations());
            AgentStep maxIterStep = AgentStep.error(ctx.getMaxIterations(), "",
                    "达到最大推理步数（" + ctx.getMaxIterations() + "），已终止");
            pushStep(maxIterStep, callback);
            pushStepsComplete(ctx, callback);
            streamFallbackAnswer("抱歉，这个问题有点复杂，我暂时无法完整回答。请尝试换一种方式提问。", callback);

        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(CANCEL_MARKER)) {
                log.info("Agent 循环被取消");
                return;
            }
            throw e;
        } catch (Exception e) {
            log.error("Agent 循环异常", e);
            callback.onError(e);
        }
    }

    // ==================== 消息构建 ====================

    /**
     * 构建 Agent 循环的初始消息列表
     * <p>
     * 顺序：System Prompt（含工具列表 + KB 上下文）→ 对话历史 → 用户问题
     */
    private List<ChatMessage> buildInitialMessages(AgentContext ctx) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. ReACT System Prompt
        ChatMessage systemPrompt = promptBuilder.build(
                toolRegistry, ctx.getKbContext(), ctx.isKbRelevant());
        messages.add(systemPrompt);

        // 2. 对话历史（含摘要）
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            messages.addAll(ctx.getHistory());
        }

        // 3. 前置指令（利用 recency bias，紧贴用户问题）
        StringBuilder reminder = new StringBuilder();
        reminder.append("【格式要求】请严格按照 ReACT 格式输出。每次输出必须包含 Thought 和 Action 字段。")
                .append("即使你不需要调用工具，也必须输出 Thought + Action: FINISH + Final Answer。")
                .append("绝对不要直接输出一段文字而不带格式标签。");
        if (ctx.isKbRelevant() && hasRagSearchTool()) {
            reminder.append("\n\n【强制检索】用户已选择知识库且问题与知识库相关。")
                    .append("你的第一轮行动必须调用 rag_search 工具检索知识库，")
                    .append("然后基于检索结果回答。不得仅凭自身知识直接回答。");
        }
        messages.add(ChatMessage.system(reminder.toString()));

        // 4. 用户当前问题
        messages.add(ChatMessage.user(ctx.getQuestion()));

        return messages;
    }

    /** 检查工具注册表中是否有 rag_search */
    private boolean hasRagSearchTool() {
        return toolRegistry.contains("rag_search");
    }

    // ==================== 流式输出 ====================

    /**
     * 流式输出最终回答
     * <p>
     * 将最终回答文本按固定大小分块，通过 callback.onContent() 逐块推送，
     * 最后调用 callback.onComplete()。
     */
    private void streamFinalAnswer(String finalAnswer, StreamCallback callback) {
        if (StrUtil.isBlank(finalAnswer)) {
            finalAnswer = "（无回答内容）";
        }
        String text = finalAnswer;
        int length = text.length();
        int pos = 0;
        try {
            while (pos < length) {
                int end = Math.min(pos + FINAL_ANSWER_CHUNK_SIZE, length);
                // 确保不截断代理对（emoji 等多字节字符）
                if (end < length && Character.isSurrogatePair(text.charAt(end - 1), text.charAt(end))) {
                    end++;
                }
                callback.onContent(text.substring(pos, end));
                pos = end;
            }
        } catch (Exception e) {
            log.warn("流式输出过程中异常: {}", e.getMessage());
        } finally {
            if (beforeCompleteCallback != null) {
                beforeCompleteCallback.run();
            }
            callback.onComplete();
        }
    }

    /**
     * 输出降级回答（流式）
     */
    private void streamFallbackAnswer(String fallbackText, StreamCallback callback) {
        streamFinalAnswer(fallbackText, callback);
    }

    // ==================== Step 推送 ====================

    /**
     * 推送 Agent 步骤到前端
     */
    private void pushStep(AgentStep step, StreamCallback callback) {
        try {
            callback.onAgentStep(step);
        } catch (Exception e) {
            log.warn("推送 Agent 步骤失败: {}", e.getMessage());
        }
    }

    /**
     * 在最终回答前推送步骤完成事件（用于持久化）
     */
    private void pushStepsComplete(AgentContext ctx, StreamCallback callback) {
        try {
            String json = GSON.toJson(ctx.getSteps());
            callback.onAgentStepsComplete(json);
        } catch (Exception e) {
            log.warn("推送 Agent 步骤完成事件失败: {}", e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
