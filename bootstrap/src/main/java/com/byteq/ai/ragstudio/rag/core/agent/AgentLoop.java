package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

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
    private final PromptTemplateLoader templateLoader;

    /** 最终回答流式输出时每次推送的字符数 */
    private static final int FINAL_ANSWER_CHUNK_SIZE = 5;

    /** Agent 提示模板路径 */
    private static final String AGENT_REMINDER_PATH = "prompt/agent-reminder.st";

    /** 取消异常标记 */
    private static final String CANCEL_MARKER = "任务已被用户取消";

    /** JSON 序列化 */
    private static final Gson GSON = new Gson();

    /** 在 onComplete 之前触发的回调（用于引用溯源等） */
    private Consumer<String> beforeCompleteCallback;

    /** 取消检查回调 */
    private final java.util.function.Supplier<Boolean> cancellationChecker;

    /** 深度思考级别（0-100），0 表示关闭 */
    private final int thinkingLevel;

    /** 是否以流式方式执行 ReACT 迭代（final_answer 内容实时透出，其余逻辑与同步一致） */
    private final boolean streamIterations;

    /** 是否已调用过 rag_search（用于抑制 kb_forced 持续生效） */
    private boolean ragSearchCalled = false;

    /** 相同工具调用缓存：key = 工具名+参数签名，value = Observation 文本（避免重复执行完全相同的调用） */
    private final Map<String, String> toolCallCache = new HashMap<>();

    /** 重复调用计数：key = 工具名+参数签名，value = 命中缓存的次数 */
    private final Map<String, Integer> toolCallRepeatCount = new HashMap<>();

    /** 同一工具最近一次 Observation（用于识别参数不同但结果相同的重复调用，如 rag_search） */
    private final Map<String, String> lastObservationByTool = new HashMap<>();

    /** 同一工具连续返回相同内容的次数（参数不同但结果相同） */
    private final Map<String, Integer> identicalResultRepeatCount = new HashMap<>();

    /** 相同（工具，参数）重复调用达到该次数后强制结束循环 */
    private static final int MAX_REPEATED_CALLS = 1;

    /** 相同工具连续返回相同内容达到该次数后强制结束循环 */
    private static final int MAX_IDENTICAL_RESULTS = 2;

    public AgentLoop(LLMService llmService,
                     ToolRegistry toolRegistry,
                     ReActResponseParser responseParser,
                     ReActPromptBuilder promptBuilder,
                     PromptTemplateLoader templateLoader) {
        this(llmService, toolRegistry, responseParser, promptBuilder, templateLoader, () -> false, 0, false);
    }

    public AgentLoop(LLMService llmService,
                     ToolRegistry toolRegistry,
                     ReActResponseParser responseParser,
                     ReActPromptBuilder promptBuilder,
                     PromptTemplateLoader templateLoader,
                     java.util.function.Supplier<Boolean> cancellationChecker) {
        this(llmService, toolRegistry, responseParser, promptBuilder, templateLoader, cancellationChecker, 0, false);
    }

    public AgentLoop(LLMService llmService,
                     ToolRegistry toolRegistry,
                     ReActResponseParser responseParser,
                     ReActPromptBuilder promptBuilder,
                     PromptTemplateLoader templateLoader,
                     java.util.function.Supplier<Boolean> cancellationChecker,
                     int thinkingLevel) {
        this(llmService, toolRegistry, responseParser, promptBuilder, templateLoader, cancellationChecker, thinkingLevel, false);
    }

    public AgentLoop(LLMService llmService,
                     ToolRegistry toolRegistry,
                     ReActResponseParser responseParser,
                     ReActPromptBuilder promptBuilder,
                     PromptTemplateLoader templateLoader,
                     java.util.function.Supplier<Boolean> cancellationChecker,
                     int thinkingLevel,
                     boolean streamIterations) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.responseParser = responseParser;
        this.promptBuilder = promptBuilder;
        this.templateLoader = templateLoader;
        this.cancellationChecker = cancellationChecker;
        this.thinkingLevel = thinkingLevel;
        this.streamIterations = streamIterations;
    }

    /** 设置在 onComplete 之前触发的回调 */
    public void setBeforeCompleteCallback(Consumer<String> callback) {
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

                // 取消检查
                if (cancellationChecker.get()) {
                    log.info("Agent 循环被用户取消，iteration={}", iteration);
                    return;
                }

                log.info("Agent 迭代 {}/{} - 消息数: {}", iteration, ctx.getMaxIterations(),
                        ctx.getMessages().size());

                // 2a. 调用 LLM（流式模式：final_answer 内容实时透出；工具迭代仅缓冲，解析逻辑与同步一致）
                String llmResponse;
                String streamedAnswer = null;
                if (streamIterations) {
                    StreamResult streamResult = streamReActIteration(ctx, callback, thinkingLevel);
                    llmResponse = streamResult.buffer();
                    streamedAnswer = streamResult.streamedAnswer();
                    if (streamResult.error() != null) {
                        // 已透出部分内容：以已透出内容正常收尾，不再回放或兜底（内容不可撤回）
                        if (streamedAnswer != null) {
                            log.warn("Agent 迭代 {} 流式中途失败，以已透出内容收尾: {}", iteration, streamResult.error().getMessage());
                            AgentStep partialStep = AgentStep.finish(iteration, "", streamedAnswer);
                            ctx.addStep(partialStep);
                            pushStepsComplete(ctx, callback);
                            finishWithStreamedAnswer(streamedAnswer, callback);
                            return;
                        }
                        handleLlmFailure(iteration, ctx, callback, streamResult.error());
                        return;
                    }
                } else {
                    try {
                        llmResponse = llmService.chat(ChatRequest.builder()
                                .messages(new ArrayList<>(ctx.getMessages()))
                                .temperature(0.4)
                                .thinkingLevel(thinkingLevel)
                                .responseFormat("json_object")
                                .build());
                    } catch (Exception e) {
                        handleLlmFailure(iteration, ctx, callback, e);
                        return;
                    }
                }

                // LLM 调用完成后立即检查取消（防止取消发生在此次调用期间）
                if (cancellationChecker.get()) {
                    log.info("Agent 迭代 {} LLM 调用后检测到取消, 终止循环", iteration);
                    return;
                }

                if (StrUtil.isBlank(llmResponse)) {
                    log.warn("Agent 迭代 {} LLM 返回空响应，自动重试一次", iteration);
                    try {
                        llmResponse = llmService.chat(ChatRequest.builder()
                                .messages(new ArrayList<>(ctx.getMessages()))
                                .temperature(0.4)
                                .thinkingLevel(0)
                                .responseFormat("json_object")
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

                // 2b. 格式校正：初次迭代 LLM 未输出可解析的 ReACT JSON 时注入纠正提示重试一次。
                // 仅当响应确实无法解析为 JSON 对象时才重试（markdown 围栏 / 前置自然语言等
                // 可被 LLMResponseCleaner 清洗的响应不再触发多余的第二次 LLM 调用）。
                // 已流式透出 final_answer 内容时不再重试（内容不可撤回）。
                boolean assistantAdded = false;
                if (iteration == 0 && streamedAnswer == null && !isReActJson(llmResponse)) {
                    log.warn("Agent 初次迭代 LLM 未使用 ReACT JSON 格式，注入纠正提示后重试");
                    ctx.addMessage(ChatMessage.assistant(llmResponse));
                    ctx.addMessage(ChatMessage.system(
                            "你刚才没有遵循 JSON 格式。请以 JSON 对象输出，"
                            + "工具调用: {\"thought\":\"...\",\"action\":\"工具名\",\"action_input\":{...}}，"
                            + "回答: {\"thought\":\"...\",\"action\":\"finish\",\"final_answer\":\"回答内容\"}"));
                    String retryResponse = null;
                    try {
                        retryResponse = llmService.chat(ChatRequest.builder()
                                .messages(new ArrayList<>(ctx.getMessages()))
                                .temperature(0.4)
                                .thinkingLevel(0)
                                .responseFormat("json_object")
                                .build());
                    } catch (Exception e2) {
                        log.warn("格式校正重试也失败: {}", e2.getMessage());
                    }
                    if (retryResponse != null && !retryResponse.isBlank()) {
                        llmResponse = retryResponse;
                    } else {
                        // 重试失败：原始响应已在上方加入消息列表，避免下方重复添加
                        assistantAdded = true;
                    }
                }

                // 2c. 解析响应
                AgentStep step = responseParser.parse(llmResponse, iteration);

                ctx.addStep(step);
                if (!assistantAdded) {
                    ctx.addMessage(ChatMessage.assistant(llmResponse));
                }

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
                    // 流式模式：final_answer 已在生成过程中实时透出，直接收尾（引用溯源 + 完成）
                    if (streamedAnswer != null) {
                        log.info("Agent 流式完成 - iteration={}, finalAnswerLength={}", iteration, streamedAnswer.length());
                        pushStepsComplete(ctx, callback);
                        if (ctx.getCollectedS3ImageUrls() != null && !ctx.getCollectedS3ImageUrls().isEmpty()) {
                            callback.setRetrievedImageUrls(new java.util.ArrayList<>(ctx.getCollectedS3ImageUrls()));
                        }
                        finishWithStreamedAnswer(streamedAnswer, callback);
                        return;
                    }

                    // LLM 输出 FINISH 但没有 final_answer 内容时重试一次
                    if (StrUtil.isBlank(step.getFinalAnswer())) {
                        log.warn("Agent FINISH 但 finalAnswer 为空，注入纠正重试");
                        ctx.addMessage(ChatMessage.assistant(llmResponse));
                        ctx.addMessage(ChatMessage.system(
                                "你输出了 action: finish 但 missing final_answer。"
                                + "请输出: {\"action\":\"finish\",\"final_answer\":\"回答内容\"}"));
                        try {
                            llmResponse = llmService.chat(ChatRequest.builder()
                                    .messages(new ArrayList<>(ctx.getMessages()))
                                    .temperature(0.4).thinkingLevel(0)
                                    .responseFormat("json_object").build());
                            step = responseParser.parse(llmResponse, iteration);
                        } catch (Exception e) { log.warn("FINISH 空回答重试失败: {}", e.getMessage()); }
                    }
                    log.info("Agent 完成 - iteration={}, finalAnswerLength={}",
                            iteration,
                            step.getFinalAnswer() != null ? step.getFinalAnswer().length() : 0);
                    pushStepsComplete(ctx, callback);
                    if (ctx.getCollectedS3ImageUrls() != null && !ctx.getCollectedS3ImageUrls().isEmpty()) {
                        callback.setRetrievedImageUrls(new java.util.ArrayList<>(ctx.getCollectedS3ImageUrls()));
                    }
                    streamFinalAnswer(step.getFinalAnswer(), callback);
                    return;
                }

                if (step.getAction() == AgentAction.TOOL_CALL) {
                    String toolName = step.getToolName();
                    String callKey = toolName + "|" + canonicalizeParams(step.getToolInput());

                    // 2e.1 完全相同的调用（工具+参数）不再重复执行，直接复用缓存结果
                    if (toolCallCache.containsKey(callKey)) {
                        int repeats = toolCallRepeatCount.merge(callKey, 1, Integer::sum);
                        String observation = toolCallCache.get(callKey);
                        log.warn("Agent 检测到相同参数重复调用工具 {}，直接复用缓存结果（第 {} 次重复）",
                                toolName, repeats);

                        step.setObservation(observation);
                        step.setDurationMs(0);
                        pushStep(step, callback);
                        ctx.addMessage(ChatMessage.observation(observation));

                        if (repeats >= MAX_REPEATED_CALLS) {
                            forceFinish(ctx, callback, iteration,
                                    "你已经多次用完全相同的参数调用工具 " + toolName + "，系统未重复执行、直接返回了相同结果。");
                            return;
                        }
                        ctx.addMessage(ChatMessage.system(
                                "警告：你刚刚用与之前完全相同的参数再次调用了工具 " + toolName
                                + "，系统未重复执行，直接返回了相同结果。请立即停止调用工具，"
                                + "基于已有的 Observation 直接输出最终回答，不要再重复调用任何工具。"));
                        continue;
                    }

                    // 2e.2 执行工具（失败时自动重试一次）
                    log.info("Agent 调用工具: {}, params={}", toolName, step.getToolInput());
                    ToolResult result = toolRegistry.execute(toolName, step.getToolInput());
                    if (!result.isSuccess()) {
                        log.warn("工具 {} 执行失败，自动重试一次: {}", toolName, result.getContent());
                        result = toolRegistry.execute(toolName, step.getToolInput());
                    }
                    String observation = result.toObservation();

                    // 2e.3 参数不同但结果与上次完全相同（如 rag_search 换 query 仍返回同一批内容），视为无效循环
                    String lastObs = lastObservationByTool.get(toolName);
                    if (lastObs != null && lastObs.equals(observation)) {
                        int identicalRepeats = identicalResultRepeatCount.merge(toolName, 1, Integer::sum);
                        log.warn("Agent 工具 {} 返回与上次完全相同的结果（第 {} 次），疑似无效循环", toolName, identicalRepeats);
                        if (identicalRepeats >= MAX_IDENTICAL_RESULTS) {
                            step.setObservation(observation);
                            step.setDurationMs(result.getDurationMs());
                            pushStep(step, callback);
                            ctx.addMessage(ChatMessage.observation(observation));
                            forceFinish(ctx, callback, iteration,
                                    "工具 " + toolName + " 多次返回与之前完全相同的结果，当前检索方式无法获取更多信息。");
                            return;
                        }
                        ctx.addMessage(ChatMessage.system(
                                "注意：工具 " + toolName + " 本次返回的结果与上次完全相同，说明当前检索方式无法获取新信息。"
                                + "请立即停止调用工具，基于已有的 Observation 直接输出最终回答。"));
                    }
                    lastObservationByTool.put(toolName, observation);

                    step.setObservation(observation);
                    step.setDurationMs(result.getDurationMs());

                    // 推送 Observation 更新
                    pushStep(step, callback);

                    // 将 Observation 追加到消息列表
                    ChatMessage obsMsg = ChatMessage.observation(observation);
                    if (CollUtil.isNotEmpty(result.getImageUrls())) {
                        obsMsg.setImageUrls(new java.util.ArrayList<>(result.getImageUrls()));
                    }
                    ctx.addMessage(obsMsg);

                    // 收集 S3 图片 URL（最终附到 assistant 回复上持久化）
                    if (CollUtil.isNotEmpty(result.getS3ImageUrls())) {
                        java.util.Set<String> existing = ctx.getCollectedS3ImageUrls();
                        if (existing == null) {
                            existing = new java.util.LinkedHashSet<>();
                            ctx.setCollectedS3ImageUrls(existing);
                        }
                        existing.addAll(result.getS3ImageUrls());
                    }

                    // 缓存本次调用结果（成功与失败都缓存，避免 LLM 无脑重试同一调用）
                    toolCallCache.put(callKey, observation);

                    // 首次 rag_search 执行后，抑制 kb_forced 持续生效，防止 Agent 重复搜索
                    if ("rag_search".equals(toolName) && !ragSearchCalled) {
                        ragSearchCalled = true;
                        ctx.addMessage(ChatMessage.system(
                                "你已成功调用 rag_search 获取了知识库检索结果。"
                                + "请基于上述 Observation 中的检索结果直接总结回答，不要再重复调用 rag_search。"));
                    }
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
            if (cancellationChecker.get()) {
                log.info("Agent 循环被取消", e);
            } else {
                log.error("Agent 循环异常", e);
            }
            callback.onError(e);
        }
    }

    // ==================== 流式迭代 ====================

    /** 流式迭代结果：完整缓冲内容 + 已实时透出的 final_answer（未透出为 null）+ 异常 */
    private record StreamResult(String buffer, String streamedAnswer, Throwable error) {
    }

    /**
     * 以流式方式执行一次 ReACT 迭代调用（阻塞至流结束）。
     * <p>
     * content 通道累积到缓冲供 JSON 解析（工具迭代只缓冲不透出，工具解析与同步路径完全一致）；
     * 当确认 action=finish 后，增量提取 final_answer 字符串值并经 callback.onContent 实时透出
     * （含 JSON 字符串转义还原：引号/反斜杠/换行/制表/回车/四位十六进制 unicode 转义）。
     * thinking 通道（deep thinking 的 reasoning_content）
     * 直接透出。未检出 final_answer（如工具调用迭代/格式异常）时静默缓冲，由调用方按现有逻辑处理。
     * </p>
     */
    private StreamResult streamReActIteration(AgentContext ctx, StreamCallback callback, int thinkingLevel) {
        ChatRequest request = ChatRequest.builder()
                .messages(new ArrayList<>(ctx.getMessages()))
                .temperature(0.4)
                .thinkingLevel(thinkingLevel)
                .responseFormat("json_object")
                .build();

        ReActJsonStreamScanner scanner = new ReActJsonStreamScanner();
        StringBuilder forwarded = new StringBuilder();
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean dropped = new java.util.concurrent.atomic.AtomicBoolean(false);

        StreamCallback wrapper = new StreamCallback() {
            @Override
            public void onContent(String content) {
                if (dropped.get() || content == null || content.isEmpty()) return;
                scanner.onContent(content);
                // thought 实时透出（仅非 deep thinking 模式：deep thinking 时 reasoning_content 已走 onThinking，
                // 避免 thought 与推理内容双通道重复）
                if (thinkingLevel == 0) {
                    String thought = scanner.drainThought();
                    if (thought != null && !thought.isEmpty()) {
                        callback.onThinking(thought);
                    }
                }
                String forward = scanner.drainAnswer();
                if (forward != null && !forward.isEmpty()) {
                    forwarded.append(forward);
                    callback.onContent(forward);
                }
            }

            @Override
            public void onThinking(String content) {
                if (dropped.get() || content == null || content.isEmpty()) return;
                callback.onThinking(content);
            }

            @Override
            public void onComplete() {
                if (dropped.get()) return;
                scanner.onComplete();
                if (thinkingLevel == 0) {
                    String thought = scanner.drainThought();
                    if (thought != null && !thought.isEmpty()) {
                        callback.onThinking(thought);
                    }
                }
                String tail = scanner.drainAnswer();
                if (tail != null && !tail.isEmpty()) {
                    forwarded.append(tail);
                    callback.onContent(tail);
                }
                latch.countDown();
            }

            @Override
            public void onError(Throwable e) {
                if (dropped.get()) return;
                if (e != null) error.set(e);
                latch.countDown();
            }

            @Override
            public void onAgentStep(Object step) {
            }

            @Override
            public void onAgentStepsComplete(String json) {
            }

            @Override
            public void onCitation(String citations) {
            }

            @Override
            public void setRetrievedImageUrls(java.util.List<String> urls) {
            }
        };

        try {
            llmService.streamChat(request, wrapper);
            // 阻塞等待流结束。不设等待上限：与同步模式语义一致——长回答/深度思考生成期间
            // 只要连接持续有数据就不应被打断；底层 HTTP 读超时（60s）保证死连接必然触发
            // onError 结束等待，不会无限挂起。
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dropped.set(true);
            error.compareAndSet(null, e);
        } catch (Exception e) {
            error.compareAndSet(null, e);
        }

        String streamedAnswer = forwarded.length() > 0 ? forwarded.toString() : null;
        return new StreamResult(scanner.bufferContent(), streamedAnswer, error.get());
    }

    /**
     * ReACT JSON 流式扫描器：顺序扫描响应 content，增量提取字符串字段值。
     * <ul>
     *   <li>{@code thought} → drainThought()（实时透出推理摘要）</li>
     *   <li>{@code final_answer}（且已确认 action=finish）→ drainAnswer()（实时透出最终回答）</li>
     *   <li>其余字段静默跳过；非字符串值（对象/数组/数字）整体跳过</li>
     * </ul>
     * 顺序扫描保证：action=finish 在 final_answer 之前被确认（ReACT 字段顺序），
     * 字段顺序异常（final_answer 先于 action）时不透出、由调用方回退为完成后回放，内容不会出错。
     * 处理 JSON 转义（\\ \" \n \t \r 及四位十六进制 unicode 转义）。
     */
    static class ReActJsonStreamScanner {

        private final StringBuilder buffer = new StringBuilder();
        private final StringBuilder thoughtOut = new StringBuilder();
        private final StringBuilder answerOut = new StringBuilder();
        private final StringBuilder actionBuf = new StringBuilder();
        private int scanPos = 0;
        /** 0=找key 1=读key名 2=等冒号 3=等值起始 4=字符串值中 5=跳过非字符串值 */
        private int state = 0;
        private StringBuilder keyBuf = null;
        private String currentKey = null;
        private boolean confirmedFinish = false;
        /** 值内转义状态（仅 state=4）：0=正常 1=刚见反斜杠 2=在 unicode 四位十六进制转义中 */
        private int escapeState = 0;
        private StringBuilder unicodeBuf = null;

        void onContent(String chunk) {
            buffer.append(chunk);
            process();
        }

        void onComplete() {
            process();
        }

        String bufferContent() {
            return buffer.toString();
        }

        /** 取回新增的 thought 透出内容并清空 */
        String drainThought() {
            return drain(thoughtOut);
        }

        /** 取回新增的 final_answer 透出内容并清空 */
        String drainAnswer() {
            return drain(answerOut);
        }

        private static String drain(StringBuilder sb) {
            if (sb.length() == 0) {
                return null;
            }
            String s = sb.toString();
            sb.setLength(0);
            return s;
        }

        private void process() {
            while (scanPos < buffer.length()) {
                char c = buffer.charAt(scanPos);
                switch (state) {
                    case 0: // 找 key 起始引号
                        if (c == '"') {
                            keyBuf = new StringBuilder();
                            state = 1;
                        }
                        scanPos++;
                        break;
                    case 1: // 读 key 名
                        if (c == '\\') {
                            keyBuf.append(c);
                            if (scanPos + 1 < buffer.length()) {
                                keyBuf.append(buffer.charAt(scanPos + 1));
                                scanPos += 2;
                            } else {
                                scanPos++;
                            }
                        } else if (c == '"') {
                            currentKey = keyBuf.toString();
                            keyBuf = null;
                            state = 2;
                            scanPos++;
                        } else {
                            keyBuf.append(c);
                            scanPos++;
                        }
                        break;
                    case 2: // 等冒号
                        if (c == ':') {
                            state = 3;
                        }
                        scanPos++;
                        break;
                    case 3: // 等值起始
                        if (c == '"') {
                            state = 4;
                            escapeState = 0;
                            unicodeBuf = null;
                            scanPos++;
                        } else if (c == '{' || c == '[' || c == 't' || c == 'f' || c == 'n'
                                || c == '-' || Character.isDigit(c)) {
                            state = 5; // 非字符串值，跳过
                        } else {
                            scanPos++; // 空白/逗号等
                        }
                        break;
                    case 4: // 字符串值中
                        if (escapeState == 2) {
                            unicodeBuf.append(c);
                            scanPos++;
                            if (unicodeBuf.length() == 4) {
                                try {
                                    appendValue((char) Integer.parseInt(unicodeBuf.toString(), 16));
                                } catch (Exception ignored) {
                                    appendValue('?');
                                }
                                unicodeBuf = null;
                                escapeState = 0;
                            }
                            break;
                        }
                        if (escapeState == 1) {
                            scanPos++;
                            if (c == 'u') {
                                unicodeBuf = new StringBuilder();
                                escapeState = 2;
                            } else {
                                appendValue(switch (c) {
                                    case 'n' -> '\n';
                                    case 't' -> '\t';
                                    case 'r' -> '\r';
                                    case '"' -> '"';
                                    case '\\' -> '\\';
                                    default -> c;
                                });
                                escapeState = 0;
                            }
                            break;
                        }
                        if (c == '\\') {
                            escapeState = 1;
                            scanPos++;
                            break;
                        }
                        if (c == '"') {
                            valueCompleted();
                            state = 0;
                            scanPos++;
                            break;
                        }
                        appendValue(c);
                        scanPos++;
                        break;
                    case 5: // 跳过非字符串值（对象/数组/数字/bool/null），直到字段分隔符
                        if (c == ',') {
                            state = 0;
                        }
                        scanPos++;
                        break;
                    default:
                        scanPos++;
                }
            }
        }

        /** 值内字符输出：仅 thought 与（已确认 finish 的）final_answer 进入透出缓冲，action 值暂存用于确认 */
        private void appendValue(char c) {
            if ("thought".equals(currentKey)) {
                thoughtOut.append(c);
            } else if ("final_answer".equals(currentKey) && confirmedFinish) {
                answerOut.append(c);
            } else if ("action".equals(currentKey)) {
                actionBuf.append(c);
            }
        }

        private void valueCompleted() {
            if ("action".equals(currentKey)) {
                String action = actionBuf.toString();
                actionBuf.setLength(0);
                // action 在 final_answer 之前被扫描（ReACT 标准字段顺序），
                // 确认 finish 后 final_answer 才会被透出
                confirmedFinish = "finish".equals(action) || "FINISH".equals(action);
            }
        }
    }

    // ==================== 消息构建 ====================

    /**
     * 构建 Agent 循环的初始消息列表
     * <p>
     * 顺序：System Prompt → 对话目标摘要 → 对话历史 → 前置指令 → 用户问题
     */
    private List<ChatMessage> buildInitialMessages(AgentContext ctx) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. ReACT System Prompt
        ChatMessage systemPrompt = promptBuilder.build(
                toolRegistry, ctx.getKbContext(), ctx.isKbRelevant());
        messages.add(systemPrompt);

        // 2. 对话目标摘要（多轮对话时注入，帮助 Agent 聚焦当前任务）
        String goalSummary = buildGoalSummary(ctx);
        if (goalSummary != null) {
            messages.add(ChatMessage.system(goalSummary));
        }

        // 3. 对话历史（含摘要）
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            messages.addAll(ctx.getHistory());
        }

        // 4. 前置指令（利用 recency bias，紧贴用户问题），从模板加载各节
        StringBuilder reminder = new StringBuilder();
        reminder.append(templateLoader.loadSection(AGENT_REMINDER_PATH, "format_reminder"));

        if (CollUtil.isNotEmpty(ctx.getHistory()) && ctx.getHistory().size() >= 4) {
            reminder.append("\n\n").append(templateLoader.loadSection(AGENT_REMINDER_PATH, "multi_turn"));
        }

        boolean hasHistoryImage = ctx.getHistory() != null && ctx.getHistory().stream()
                .anyMatch(m -> m.getImageUrls() != null && !m.getImageUrls().isEmpty());
        if (hasHistoryImage) {
            reminder.append("\n\n").append(templateLoader.loadSection(AGENT_REMINDER_PATH, "image_history"));
        }

        if (ctx.isKbRelevant() && hasRagSearchTool()) {
            reminder.append("\n\n").append(templateLoader.loadSection(AGENT_REMINDER_PATH, "kb_forced"));
        }
        messages.add(ChatMessage.system(reminder.toString()));

        // 5. 用户当前问题（含图片 URL）
        ChatMessage userMsg = ChatMessage.user(ctx.getQuestion());
        if (CollUtil.isNotEmpty(ctx.getImageUrls())) {
            userMsg.setImageUrls(new java.util.ArrayList<>(ctx.getImageUrls()));
        }
        messages.add(userMsg);

        return messages;
    }

    /**
     * 从对话历史中提取当前目标摘要，帮助 Agent 在后续轮次中保持上下文感知
     */
    private String buildGoalSummary(AgentContext ctx) {
        if (CollUtil.isEmpty(ctx.getHistory())) {
            return null;
        }
        List<String> userQuestions = new ArrayList<>();
        List<String> imageDescriptions = new ArrayList<>();
        for (int i = 0; i < ctx.getHistory().size(); i++) {
            ChatMessage msg = ctx.getHistory().get(i);
            boolean hasImage = msg.getImageUrls() != null && !msg.getImageUrls().isEmpty();
            if (msg.getRole() == ChatMessage.Role.USER && StrUtil.isNotBlank(msg.getContent())) {
                String content = msg.getContent().trim();
                if (!content.startsWith("Observation:") && !content.startsWith("{\"query\"") && !content.contains("[^chunk_")) {
                    userQuestions.add(content);
                    // 如果这条用户消息有图片，找下一条 assistant 消息作为图片描述
                    if (hasImage && i + 1 < ctx.getHistory().size()) {
                        ChatMessage next = ctx.getHistory().get(i + 1);
                        if (next.getRole() == ChatMessage.Role.ASSISTANT && StrUtil.isNotBlank(next.getContent())) {
                            imageDescriptions.add(next.getContent().trim());
                        }
                    }
                }
            }
        }
        if (userQuestions.isEmpty() && imageDescriptions.isEmpty()) {
            return null;
        }
        String previousQuestions = String.join(" → ", userQuestions);
        String imageNote = "";
        if (!imageDescriptions.isEmpty()) {
            imageNote = "（用户之前上传了图片，分析结果：" + String.join("；", imageDescriptions) + "）。";
        }
        return templateLoader.renderSection(AGENT_REMINDER_PATH, "goal_summary", Map.of(
                "previous_questions", previousQuestions,
                "image_note", imageNote
        ));
    }

    /** 检查工具注册表中是否有 rag_search */
    private boolean hasRagSearchTool() {
        return toolRegistry.contains("rag_search");
    }

    /**
     * 判断 LLM 响应是否可解析为 ReACT JSON 对象。
     * 可容忍 markdown 代码块围栏、前置自然语言等可通过 LLMResponseCleaner 清洗的形式，
     * 避免这些无害格式触发多余的"格式纠正"第二次 LLM 调用。
     */
    private boolean isReActJson(String raw) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw).trim();
        String json = LLMResponseCleaner.extractJson(cleaned);
        if (json == null || !json.startsWith("{")) {
            return false;
        }
        try {
            return JsonParser.parseString(json).isJsonObject();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 无效循环防护 ====================

    /**
     * 检测到 Agent 陷入无效重复调用（相同工具相同参数 / 工具结果完全不变）时，
     * 强制要求 LLM 基于已有 Observation 直接给出最终回答，结束循环。
     * <p>
     * 若 LLM 仍未能产出合法 final_answer，则使用兜底回答结束。
     * </p>
     */
    private void forceFinish(AgentContext ctx, StreamCallback callback, int iteration, String reason) {
        log.warn("Agent 检测到无效重复调用，强制结束循环: {}", reason);
        ctx.addMessage(ChatMessage.system(reason
                + " 你必须立即停止调用任何工具，仅基于当前对话中已有的 Observation 给出最终回答。"
                + " 请输出 JSON: {\"action\":\"finish\",\"final_answer\":\"你的最终回答\"}。"));
        try {
            String llmResponse = llmService.chat(ChatRequest.builder()
                    .messages(new ArrayList<>(ctx.getMessages()))
                    .temperature(0.4)
                    .thinkingLevel(0)
                    .responseFormat("json_object")
                    .build());
            if (StrUtil.isNotBlank(llmResponse)) {
                AgentStep finalStep = responseParser.parse(llmResponse, iteration);
                if (finalStep != null && finalStep.getAction() == AgentAction.FINISH
                        && StrUtil.isNotBlank(finalStep.getFinalAnswer())) {
                    ctx.addStep(finalStep);
                    ctx.addMessage(ChatMessage.assistant(llmResponse));
                    pushStep(finalStep, callback);
                    pushStepsComplete(ctx, callback);
                    streamFinalAnswer(finalStep.getFinalAnswer(), callback);
                    return;
                }
            }
            log.warn("强制结束重试未获得合法 final_answer: {}", llmResponse);
        } catch (Exception e) {
            log.warn("强制结束 LLM 调用失败: {}", e.getMessage());
        }
        pushStepsComplete(ctx, callback);

        // 直接问答模式兜底：绕过 ReACT JSON 协议，仅基于已有 Observation 让 LLM 直接作答。
        // 弱模型在 ReACT 协议下容易反复输出工具调用而非 final_answer，但同样的内容
        // 在普通问答格式下可以正常作答，避免直接落入"未检索到"的兜底话术。
        String directAnswer = tryDirectAnswerFromObservations(ctx);
        if (StrUtil.isNotBlank(directAnswer)) {
            streamFinalAnswer(directAnswer, callback);
            return;
        }
        streamFallbackAnswer(
                "抱歉，我没有找到足够的信息来回答这个问题。请尝试换一种方式提问，或检查知识库内容是否完整。",
                callback);
    }

    /**
     * 直接问答兜底：收集循环中已产生的全部 Observation，以普通问答格式让 LLM 作答。
     * 返回 null 表示仍无有效回答（上层继续使用兜底话术）。
     */
    private String tryDirectAnswerFromObservations(AgentContext ctx) {
        StringBuilder evidence = new StringBuilder();
        for (ChatMessage msg : ctx.getMessages()) {
            if (msg.getRole() == ChatMessage.Role.OBSERVATION && StrUtil.isNotBlank(msg.getContent())) {
                String content = msg.getContent().trim();
                if (content.startsWith("Observation:")) {
                    content = content.substring("Observation:".length()).trim();
                }
                evidence.append(content).append("\n\n");
            }
        }
        if (evidence.length() == 0) {
            log.warn("直接问答兜底跳过：无任何 Observation 内容");
            return null;
        }

        String systemPrompt;
        try {
            systemPrompt = templateLoader.load("prompt/answer-chat-system.st");
        } catch (Exception e) {
            log.warn("直接问答兜底跳过：加载 answer-chat-system 模板失败: {}", e.getMessage());
            return null;
        }

        String userContent = "【检索知识】\n" + evidence + "【用户问题】\n" + ctx.getQuestion();
        try {
            String response = llmService.chat(ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt),
                            ChatMessage.user(userContent)))
                    .temperature(0.3)
                    .thinkingLevel(0)
                    .build());
            if (StrUtil.isBlank(response)) {
                return null;
            }
            String text = response.trim();
            // 防御：模型仍输出 JSON 包装时提取 final_answer / 文本
            if (text.startsWith("{") && text.contains("\"final_answer\"")) {
                String extracted = extractFinalAnswerFromRawJson(text);
                if (extracted != null) {
                    text = extracted;
                }
            }
            log.info("直接问答兜底成功，回答长度: {}", text.length());
            return text;
        } catch (Exception e) {
            log.warn("直接问答兜底调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将工具参数规范化为字符串签名（按键排序，保证顺序无关的一致性）
     */
    private String canonicalizeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        TreeMap<String, Object> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append('=');
            Object value = entry.getValue();
            if (value instanceof Map) {
                sb.append(canonicalizeParams((Map<String, Object>) value));
            } else {
                sb.append(value == null ? "null" : value.toString());
            }
            sb.append(';');
        }
        return sb.toString();
    }

    // ==================== 流式输出 ====================

    /**
     * 迭代 LLM 调用失败的统一处理（未透出任何内容时）：推送错误步骤 + 兜底回答
     */
    private void handleLlmFailure(int iteration, AgentContext ctx, StreamCallback callback, Throwable e) {
        // 用户取消时不刷 error
        if (cancellationChecker.get()) {
            log.info("Agent 迭代 {} LLM 调用被取消: {}", iteration, e.getMessage());
        } else {
            log.error("Agent 迭代 {} LLM 调用失败: {}", iteration, e.getMessage());
        }
        AgentStep errorStep = AgentStep.error(iteration, "",
                "模型调用失败: " + e.getMessage());
        pushStep(errorStep, callback);
        pushStepsComplete(ctx, callback);
        streamFallbackAnswer("抱歉，模型服务暂时不可用，请稍后重试。", callback);
    }

    /**
     * 流式模式收尾：final_answer 已实时透出，仅触发引用溯源与完成事件，不再回放内容
     */
    private void finishWithStreamedAnswer(String answer, StreamCallback callback) {
        if (beforeCompleteCallback != null) {
            beforeCompleteCallback.accept(answer);
        }
        callback.onComplete();
    }

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
        // 防御：如果 finalAnswer 是未解析的 JSON，提取 final_answer 字段
        String text = finalAnswer;
        if (text.startsWith("{") && text.contains("\"final_answer\"")) {
            text = extractFinalAnswerFromRawJson(text);
            if (text == null) text = finalAnswer;
        }
        int length = text.length();
        int pos = 0;
        boolean streamOk = true;
        try {
            while (pos < length) {
                int end = Math.min(pos + FINAL_ANSWER_CHUNK_SIZE, length);
                if (end < length && Character.isHighSurrogate(text.charAt(end - 1))) {
                    end++;
                }
                callback.onContent(text.substring(pos, end));
                pos = end;
            }
        } catch (Exception e) {
            streamOk = false;
            log.warn("流式输出过程中异常: {}", e.getMessage());
        } finally {
            if (streamOk) {
                if (beforeCompleteCallback != null) {
                    beforeCompleteCallback.accept(finalAnswer);
                }
                callback.onComplete();
            }
        }
    }

    /**
     * 兜底回答流式输出
     */
    private void streamFallbackAnswer(String fallbackText, StreamCallback callback) {
        streamFinalAnswer(fallbackText, callback);
    }

    /**
     * 从原始 JSON 中提取 final_answer，作为最后一层兜底
     * @return 提取出的文本，如果提取失败返回 null
     */
    private String extractFinalAnswerFromRawJson(String text) {
        try {
            com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(text);
            if (el.isJsonObject()) {
                var obj = el.getAsJsonObject();
                var fa = obj.get("final_answer");
                if (fa != null && !fa.isJsonNull() && StrUtil.isNotBlank(fa.getAsString())) {
                    return fa.getAsString();
                }
            }
        } catch (Exception e) { return null; }
        // 纯正则兜底
        var m = java.util.regex.Pattern.compile("\"final_answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(text);
        if (m.find()) {
            return m.group(1).replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return null;
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
