package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.DefaultModelService;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.framework.trace.TraceStatus;
import com.byteq.ai.ragstudio.infra.agentscope.AgentScopeModelFactory;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.model.ModelHealthStore;
import com.byteq.ai.ragstudio.infra.model.ModelSelector;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.reasoning.ReasoningRouter;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateUtils;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrievalEngine;
import com.byteq.ai.ragstudio.rag.core.skill.SandboxExecutor;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDefinition;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.core.skill.SkillTool;
import com.byteq.ai.ragstudio.rag.core.skill.ToolReaderTool;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolNameUtil;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import com.byteq.ai.ragstudio.rag.service.handler.StreamTaskManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentScope ReActAgent 执行器
 * <p>
 * 替代自研 JSON 结构化 ReACT 循环，使用 AgentScope 原生
 * 工具调用驱动的 ReActAgent 执行问答：
 * <ul>
 *   <li>按请求构建 ReActAgent（模型 = 路由选择的主模型 + fallback 模型）</li>
 *   <li>工具注册：rag_search / time_now / tool_reader / MCP / SKILL（复用现有执行逻辑）</li>
 *   <li>streamEvents() 事件流 → SSE 协议（content / think / agent_step / citation）</li>
 *   <li>取消通过 taskManager 绑定 interrupt()，中断进行中的推理</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class AgentScopeReActExecutor {

    private static final String REACT_SYSTEM_PROMPT_PATH = "prompt/react-system-agentscope.st";
    private static final String AGENT_REMINDER_PATH = "prompt/agent-reminder.st";

    private static final String NO_TOOLS_TEXT = "当前没有可用工具。";
    private static final String NO_KB_TEXT = "（无预检索知识库内容）";
    private static final String KB_IRRELEVANT_NOTE =
            "> ⚠️ 注意：用户问题经判断与所选知识库**不相关**，已跳过知识库检索。请不要尝试使用 rag_search 工具，"
            + "也不要输出任何 [^chunk_{id}] 引用标记。";
    private static final String KB_RELEVANT_NOTE =
            "> ⚠️ 用户已选择知识库且问题与知识库相关。**你的第一轮行动必须调用 rag_search 工具检索知识库**，然后基于检索结果回答。不得仅凭自身知识直接回答。";
    private static final String SEARCH_PRIORITY_WITH_RAG = "先 `rag_search`，不够再 `web-search` 或其他";
    private static final String SEARCH_PRIORITY_WITHOUT_RAG = "使用可用工具搜索相关数据";

    private static final String CANCEL_MARKER = "任务已被用户取消";

    private static final String TRACE_STATUS_RUNNING = TraceStatus.RUNNING.name();
    private static final String TRACE_STATUS_SUCCESS = TraceStatus.SUCCESS.name();

    private static final Pattern CHUNK_REF_PATTERN = Pattern.compile("\\[\\^chunk_(\\w+)\\]");

    /** Final Answer 分隔标记：兼容 "Final Answer:" / "Final Answer：" / "最终回答：" / "最终答案：" */
    private static final Pattern FINAL_ANSWER_MARKER_PATTERN =
            Pattern.compile("(?i)(?:final answer|最终回答|最终答案)\\s*[:：]\\s*");

    /**
     * 最终回答增量直透阈值（字符）：
     * 单次迭代累积文本达到该长度且尚未出现工具调用时，判定该迭代为最终回答流，
     * 后续文本增量直透 SSE。评测观测工具调用迭代的前置文本恒为空（thought=""），
     * 64 字符内误判为最终回答的概率极低，可换取长回答"生成即显示"的感知收益。
     */
    private static final int INCREMENTAL_CONTENT_THRESHOLD = 64;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private final AgentScopeModelFactory modelFactory;
    private final ModelSelector selector;
    private final DefaultModelService defaultModelService;
    private final ReasoningRouter reasoningRouter;
    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final McpToolRegistry mcpToolRegistry;
    private final SkillLoader skillLoader;
    private final okhttp3.OkHttpClient syncHttpClient;
    private final PromptTemplateLoader templateLoader;
    private final StreamTaskManager taskManager;
    private final RagTraceRecordService traceRecordService;
    private final RagTraceProperties traceProperties;
    private final ModelHealthStore healthStore;

    public AgentScopeReActExecutor(
            AgentScopeModelFactory modelFactory,
            ModelSelector selector,
            DefaultModelService defaultModelService,
            ReasoningRouter reasoningRouter,
            RetrievalEngine retrievalEngine,
            SearchChannelProperties searchProperties,
            McpToolRegistry mcpToolRegistry,
            SkillLoader skillLoader,
            okhttp3.OkHttpClient syncHttpClient,
            PromptTemplateLoader templateLoader,
            StreamTaskManager taskManager,
            RagTraceRecordService traceRecordService,
            RagTraceProperties traceProperties,
            ModelHealthStore healthStore) {
        this.modelFactory = modelFactory;
        this.selector = selector;
        this.defaultModelService = defaultModelService;
        this.reasoningRouter = reasoningRouter;
        this.retrievalEngine = retrievalEngine;
        this.searchProperties = searchProperties;
        this.mcpToolRegistry = mcpToolRegistry;
        this.skillLoader = skillLoader;
        this.syncHttpClient = syncHttpClient;
        this.templateLoader = templateLoader;
        this.taskManager = taskManager;
        this.traceRecordService = traceRecordService;
        this.traceProperties = traceProperties;
        this.healthStore = healthStore;
    }

    /**
     * 执行 AgentScope ReActAgent 问答循环（非阻塞订阅，SSE 事件透传）
     * <p>
     * 返回的 {@link CompletableFuture} 在 Agent 事件流完全结束（onComplete / onError / 取消）
     * 后才完成。调用方（StreamChatPipeline）通过 join 等待，从而：
     * <ul>
     *   <li>“Agent循环”trace 节点记录的是完整 Agent 时长，而非仅订阅返回</li>
     *   <li>限流 permit 与会话并发锁自然持有到流式回答真正结束，max-concurrent 真正约束活跃流数量</li>
     * </ul>
     * </p>
     *
     * @param ctx            Agent 上下文
     * @param taskId         任务 ID（用于取消）
     * @param sandboxExecutor SKILL 沙箱执行器（script/command 类型）
     * @param sandboxEnabled 沙箱总开关（false 时 script/command 类型 SKILL 拒绝执行）
     * @param allowedCommandPrefixes command 类型命令前缀白名单（空表示禁用 command 类型）
     * @param callback       SSE 流式回调
     */
    public CompletableFuture<Void> run(AgentContext ctx, String taskId, SandboxExecutor sandboxExecutor,
                                       boolean sandboxEnabled, List<String> allowedCommandPrefixes,
                                       StreamCallback callback) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (taskManager.isCancelled(taskId)) {
            log.info("任务已被取消，跳过 Agent 执行，任务ID：{}", taskId);
            done.complete(null);
            return done;
        }

        RunState state = new RunState(taskId, ctx);
        // 标记 Agent 循环开始时间（供总超时 watchdog 与后续统计使用）
        ctx.markStart();
        // 捕获当前链路的 traceId 与父节点（Agent循环）ID：
        // AgentScope 事件回调在独立线程执行，ThreadLocal 不传递，需在启动线程快照
        state.traceId = RagTraceContext.getTraceId();
        state.agentLoopParentNodeId = RagTraceContext.currentNodeId();
        try {
            // 1. 选择模型（主模型 + fallback）
            List<ModelTarget> targets = selectTargets(ctx);
            ModelTarget primary = targets.get(0);
            ModelTarget fallback = targets.size() > 1 ? targets.get(1) : null;
            Model model = modelFactory.buildChatModel(primary);
            Model fallbackModel = fallback != null ? modelFactory.buildChatModel(fallback) : null;

            // 2. 构建工具集
            Toolkit toolkit = buildToolkit(ctx, state, sandboxExecutor, sandboxEnabled, allowedCommandPrefixes);

            // 3. 构建 System Prompt（含目标摘要、历史摘要、前置指令——AgentScope 输入不允许 SYSTEM 消息）
            String sysPrompt = buildSystemPrompt(ctx, state.toolNames);

            // 4. 构建 ReActAgent
            ReActAgent agent = ReActAgent.builder()
                    .name("qa")
                    .sysPrompt(sysPrompt)
                    .model(model)
                    .fallbackModel(fallbackModel)
                    .toolkit(toolkit)
                    .maxIters(ctx.getMaxIterations())
                    .generateOptions(buildGenerateOptions(ctx, primary))
                    .modelExecutionConfig(ExecutionConfig.builder()
                            .timeout(Duration.ofMillis(ctx.getTimeoutMs()))
                            .build())
                    .defaultSessionId(taskId)
                    .build();

            // 5. 构建消息列表并订阅事件流
            List<Msg> msgs = buildMessages(ctx);
            RuntimeContext runtimeCtx = RuntimeContext.builder()
                    .sessionId(taskId)
                    .userId(ctx.getUserId())
                    .build();

            AtomicBoolean terminal = new AtomicBoolean(false);
            AtomicBoolean maxItersExceeded = new AtomicBoolean(false);

            agent.streamEvents(msgs, runtimeCtx)
                    .subscribe(
                            event -> onEvent(event, state, callback, maxItersExceeded),
                            error -> {
                                try {
                                    // 模型调用/传输失败：标记熔断失败（用户主动取消不算模型故障）
                                    if (!taskManager.isCancelled(taskId)) {
                                        healthStore.markFailure(primary.id());
                                    }
                                    onError(error, state, callback, terminal);
                                } finally {
                                    // 无论回调是否异常都必须完成句柄，否则调用方 join 永久阻塞、
                                    // 限流线程被占死（chatEntryExecutor 仅 max-concurrent 个）
                                    done.complete(null);
                                }
                            },
                            () -> {
                                try {
                                    onTerminal(state, callback, terminal, maxItersExceeded);
                                } finally {
                                    // 正常收尾：关闭熔断探测（清除 HALF_OPEN in-flight），避免恢复后的模型被误限流
                                    if (!taskManager.isCancelled(taskId)) {
                                        healthStore.markSuccess(primary.id());
                                    }
                                    done.complete(null);
                                }
                            });

            // 6. 绑定取消句柄：取消时中断进行中的推理
            taskManager.bindHandle(taskId, (StreamCancellationHandle) () ->
                    agent.interrupt(ctx.getUserId(), taskId));

            // 7. 总执行超时 watchdog：AgentScope 的 ExecutionConfig.timeout 仅约束单次模型调用，
            // 多轮工具调用累计可能远超预期，这里对整段循环施加总超时，到期强制取消 Agent
            long agentTimeoutMs = ctx.getTimeoutMs();
            if (agentTimeoutMs > 0) {
                CompletableFuture.delayedExecutor(agentTimeoutMs, TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            if (!done.isDone()) {
                                log.warn("Agent 总执行超时（{}ms），强制取消任务: taskId={}", agentTimeoutMs, taskId);
                                taskManager.cancel(taskId);
                            }
                        });
            }
        } catch (RemoteException e) {
            log.warn("Agent 执行前置校验失败: {}", e.getMessage());
            pushModelUnavailable(callback);
            done.complete(null);
        } catch (Exception e) {
            if (taskManager.isCancelled(taskId)) {
                log.info("任务已被取消，任务ID：{}", taskId);
            } else {
                log.error("Agent 执行器启动失败", e);
                callback.onError(e);
            }
            done.complete(null);
        }
        return done;
    }

    // ==================== 事件处理 ====================

    private void onEvent(AgentEvent event, RunState state, StreamCallback callback,
                         AtomicBoolean maxItersExceeded) {
        try {
            if (event instanceof ModelCallStartEvent) {
                state.modelCallCount.incrementAndGet();
                state.iterationText.setLength(0);
                state.iterationHadToolCall.set(false);
                state.incrementalContent.set(false);
                // 每次模型调用单独建节点：Agent 循环内部的 LLM 耗时不再不可见
                state.modelCallNodeIds.put(state.modelCallCount.get(),
                        startTraceNode(state, "LLM_CALL", "Agent模型调用#" + state.modelCallCount.get()));
            } else if (event instanceof ModelCallEndEvent) {
                finishTraceNode(state, state.modelCallNodeIds.remove(state.modelCallCount.get()));
                // 纯文本迭代（无工具调用）→ 该文本即为最终回答，透出到 content 通道
                if (!state.iterationHadToolCall.get() && state.iterationText.length() > 0) {
                    flushIterationText(state, callback);
                }
            } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
                String delta = thinking.getDelta();
                if (StrUtil.isNotBlank(delta)) {
                    state.thinkingBuffer.append(delta);
                    callback.onThinking(delta);
                }
            } else if (event instanceof TextBlockDeltaEvent text) {
                // 缓冲当前迭代文本；若随后出现工具调用则转为 think 透出，
                // 避免"调用工具前的思考文本"污染最终回答内容
                String delta = text.getDelta();
                if (StrUtil.isNotBlank(delta)) {
                    if (state.incrementalContent.get()) {
                        // 已确认最终回答流：增量直透，保持流式输出
                        state.answerBuffer.append(delta);
                        callback.onContent(delta);
                    } else {
                        state.iterationText.append(delta);
                        // 长文本且未出现工具调用 → 判定为最终回答流，提前增量透出，
                        // 避免整段回答生成期间用户零反馈（生成完毕才一次性显示）
                        if (!state.iterationHadToolCall.get()
                                && state.iterationText.length() >= INCREMENTAL_CONTENT_THRESHOLD) {
                            // 剥离 Final Answer 标记后再透出，避免标记泄漏到用户可见回答
                            String flushed = extractFinalAnswerText(state.iterationText.toString());
                            state.iterationText.setLength(0);
                            if (StrUtil.isNotBlank(flushed)) {
                                state.answerBuffer.append(flushed);
                                state.incrementalContent.set(true);
                                callback.onContent(flushed);
                            }
                            // 剥离后为空（仅到达 "Final Answer:" 前缀）：继续缓冲等待正文，不进入增量模式
                        }
                    }
                }
            } else if (event instanceof ToolCallStartEvent toolStart) {
                // 工具调用前的文本 → think 通道（推理摘要）
                if (state.iterationText.length() > 0) {
                    callback.onThinking(state.iterationText.toString());
                    state.iterationText.setLength(0);
                }
                state.iterationHadToolCall.set(true);
                state.pendingToolCalls.put(toolStart.getToolCallId(),
                        new PendingToolCall(toolStart.getToolCallName(), new StringBuilder()));
            } else if (event instanceof ToolCallDeltaEvent toolDelta) {
                PendingToolCall pending = state.pendingToolCalls.get(toolDelta.getToolCallId());
                if (pending != null && toolDelta.getDelta() != null) {
                    pending.argsBuffer.append(toolDelta.getDelta());
                }
            } else if (event instanceof ToolCallEndEvent toolEnd) {
                PendingToolCall pending = state.pendingToolCalls.remove(toolEnd.getToolCallId());
                if (pending != null) {
                    Map<String, Object> args = parseToolArgs(pending.argsBuffer.toString());
                    int iteration = state.modelCallCount.get() - 1;
                    String thought = drain(state.thinkingBuffer);
                    AgentStep step = AgentStep.toolCall(iteration, thought, pending.name, args);
                    state.steps.add(step);
                    state.stepByToolCall.put(toolEnd.getToolCallId(), step);
                    state.toolCallStartTimes.put(toolEnd.getToolCallId(), System.currentTimeMillis());
                    // 工具调用节点：rag_search 等耗时数秒的工具在链路中可见
                    state.toolCallNodeIds.put(toolEnd.getToolCallId(),
                            startTraceNode(state, "TOOL_CALL", "工具[" + pending.name + "]"));
                    pushStep(step, callback);
                    log.info("AgentScope 工具调用: iteration={}, tool={}, args={}",
                            iteration, pending.name, args);
                }
            } else if (event instanceof ToolResultTextDeltaEvent resultDelta) {
                state.toolResultBuffers.computeIfAbsent(resultDelta.getToolCallId(),
                        k -> new StringBuilder()).append(resultDelta.getDelta());
            } else if (event instanceof ToolResultEndEvent resultEnd) {
                AgentStep step = state.stepByToolCall.get(resultEnd.getToolCallId());
                if (step != null) {
                    StringBuilder buf = state.toolResultBuffers.remove(resultEnd.getToolCallId());
                    String observation = buf != null ? buf.toString() : "";
                    step.setObservation(observation);
                    Long start = state.toolCallStartTimes.remove(resultEnd.getToolCallId());
                    if (start != null) {
                        step.setDurationMs(System.currentTimeMillis() - start);
                    }
                    pushStep(step, callback);
                }
                finishTraceNode(state, state.toolCallNodeIds.remove(resultEnd.getToolCallId()));
            } else if (event instanceof ExceedMaxItersEvent) {
                maxItersExceeded.set(true);
                log.warn("Agent 达到最大迭代次数，强制终止");
            } else if (event instanceof AgentResultEvent resultEvent) {
                state.finalMsg = resultEvent.getResult();
            } else if (event instanceof AgentEndEvent) {
                state.agentEnded.set(true);
            }
        } catch (Exception e) {
            log.warn("AgentScope 事件处理异常: {}", e.getMessage());
        }
    }

    private void onError(Throwable error, RunState state, StreamCallback callback, AtomicBoolean terminal) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        if (taskManager.isCancelled(state.taskId)) {
            log.info("AgentScope 流在取消后终止，任务ID：{}", state.taskId);
            return;
        }
        // 已透出部分内容：以已透出内容正常收尾（内容不可撤回）
        if (state.answerBuffer.length() > 0) {
            log.warn("AgentScope 流式中途失败，以已透出内容收尾: {}", error.getMessage());
            finishStream(state, callback);
            return;
        }
        log.error("AgentScope 执行失败", error);
        callback.onError(error);
    }

    private void onTerminal(RunState state, StreamCallback callback, AtomicBoolean terminal,
                            AtomicBoolean maxItersExceeded) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        if (taskManager.isCancelled(state.taskId)) {
            log.info("AgentScope 执行完成但任务已取消，任务ID：{}", state.taskId);
            return;
        }
        // 安全收尾：未触发的迭代文本（正常流程已在 MODEL_CALL_END 透出）
        if (state.iterationText.length() > 0) {
            flushIterationText(state, callback);
        }
        if (maxItersExceeded.get()) {
            log.warn("AgentScope 达到最大迭代次数，推送兜底回答");
            AgentStep errorStep = AgentStep.error(state.steps.size(), "",
                    "达到最大推理步数（" + state.ctx.getMaxIterations() + "），已终止");
            pushStep(errorStep, callback);
            pushStepsComplete(state, callback);
            streamText("抱歉，这个问题有点复杂，我暂时无法完整回答。请尝试换一种方式提问。", state, callback);
            return;
        }
        finishStream(state, callback);
    }

    /** 透出迭代文本到 content 通道并累计到回答缓冲（若含 Final Answer 标记则只透出标记后的最终回答） */
    private void flushIterationText(RunState state, StreamCallback callback) {
        if (state.iterationText.length() == 0) {
            return;
        }
        String text = extractFinalAnswerText(state.iterationText.toString());
        state.iterationText.setLength(0);
        if (StrUtil.isNotBlank(text)) {
            state.answerBuffer.append(text);
            callback.onContent(text);
        }
    }

    /**
     * 从模型原始输出中提取最终回答正文：
     * 自由文本 ReACT 模式下模型可能先输出思维链再以 "Final Answer：..." 收尾，
     * 若检测到 "Final Answer"（兼容中英文冒号）或 "最终回答/最终答案" 标记，
     * 则只保留最后一次标记之后的内容，避免思维链泄漏到用户可见的回答中。
     * 未检测到标记时原样返回（如澄清式提问、无标记的简短回答）。
     */
    private String extractFinalAnswerText(String raw) {
        if (StrUtil.isBlank(raw)) {
            return raw;
        }
        Matcher m = FINAL_ANSWER_MARKER_PATTERN.matcher(raw);
        int split = -1;
        while (m.find()) {
            split = m.end();
        }
        if (split < 0) {
            return raw;
        }
        String tail = raw.substring(split).trim();
        return StrUtil.isBlank(tail) ? raw : tail;
    }

    private void finishStream(RunState state, StreamCallback callback) {
        String finalAnswer = extractFinalAnswer(state);
        if (StrUtil.isBlank(finalAnswer)) {
            finalAnswer = "（无回答内容）";
        }
        AgentStep finishStep = AgentStep.finish(state.modelCallCount.get() - 1,
                drain(state.thinkingBuffer), finalAnswer);
        state.steps.add(finishStep);
        pushStep(finishStep, callback);
        pushStepsComplete(state, callback);

        // 引用溯源
        fireCitations(state, finalAnswer, callback);

        // 检索图片 URL 附到 assistant 回复上持久化
        if (!state.s3ImageUrls.isEmpty()) {
            callback.setRetrievedImageUrls(new ArrayList<>(state.s3ImageUrls));
        }
        callback.onComplete();
    }

    private String extractFinalAnswer(RunState state) {
        Msg msg = state.finalMsg;
        if (msg != null && msg.getContent() != null) {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock text && text.getText() != null) {
                    sb.append(text.getText());
                }
            }
            if (sb.length() > 0) {
                return extractFinalAnswerText(sb.toString());
            }
        }
        return state.answerBuffer.toString();
    }

    private void streamText(String text, RunState state, StreamCallback callback) {
        callback.onContent(text);
        AgentStep finishStep = AgentStep.finish(state.modelCallCount.get() - 1, "", text);
        state.steps.add(finishStep);
        pushStep(finishStep, callback);
        pushStepsComplete(state, callback);
        callback.onComplete();
    }

    private void pushModelUnavailable(StreamCallback callback) {
        callback.onContent("抱歉，模型服务暂时不可用，请稍后重试。");
        callback.onComplete();
    }

    // ==================== 引用溯源 ====================

    private void fireCitations(RunState state, String finalAnswer, StreamCallback callback) {
        if (state.retrievedChunks.isEmpty() && StrUtil.isBlank(finalAnswer)) {
            return;
        }
        try {
            List<String> referencedIds = new ArrayList<>();
            if (StrUtil.isNotBlank(finalAnswer)) {
                Matcher m = CHUNK_REF_PATTERN.matcher(finalAnswer);
                while (m.find()) {
                    String id = m.group(1);
                    if (!referencedIds.contains(id)) {
                        referencedIds.add(id);
                    }
                }
            }

            List<Map<String, Object>> citations = new ArrayList<>();
            Set<String> seenChunkIds = new java.util.HashSet<>();
            if (!referencedIds.isEmpty()) {
                for (String id : referencedIds) {
                    RetrievedChunk c = null;
                    try {
                        int idx = Integer.parseInt(id) - 1;
                        if (idx >= 0 && idx < state.retrievedChunks.size()) {
                            c = state.retrievedChunks.get(idx);
                        }
                    } catch (NumberFormatException ignored) {
                        for (RetrievedChunk rc : state.retrievedChunks) {
                            if (id.equals(rc.getId())) {
                                c = rc;
                                break;
                            }
                        }
                    }
                    if (c == null || c.getId() == null) {
                        continue;
                    }
                    if (!seenChunkIds.add(c.getId())) {
                        continue;
                    }
                    citations.add(toCitationEntry(id, c));
                }
            } else {
                for (RetrievedChunk c : state.retrievedChunks) {
                    if (c.getId() != null && !seenChunkIds.add(c.getId())) {
                        continue;
                    }
                    if (c.isImage()) {
                        citations.add(toCitationEntry(c.getId() != null ? c.getId() : "", c));
                        continue;
                    }
                    if (StrUtil.isBlank(c.getText())) {
                        continue;
                    }
                    String chunkText = c.getText();
                    boolean matched = false;
                    for (int i = 0; i <= chunkText.length() - 10 && !matched; i++) {
                        if (StrUtil.isNotBlank(finalAnswer)
                                && finalAnswer.contains(chunkText.substring(i, i + 10))) {
                            matched = true;
                        }
                    }
                    if (matched) {
                        citations.add(toCitationEntry(c.getId() != null ? c.getId() : "", c));
                    }
                }
            }

            if (!citations.isEmpty()) {
                String json = OBJECT_MAPPER.writeValueAsString(citations);
                callback.onCitation(json);
            }
        } catch (Exception e) {
            log.warn("AgentScope 引用溯源失败", e);
        }
    }

    private Map<String, Object> toCitationEntry(String id, RetrievedChunk c) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("chunkId", c.getId() != null ? c.getId() : "");
        entry.put("text", c.getText() != null ? c.getText() : "");
        entry.put("score", c.getScore() != null ? c.getScore() : 0f);
        entry.put("kbName", c.getKbName() != null ? c.getKbName() : "");
        entry.put("docName", c.getDocName() != null ? c.getDocName() : "");
        entry.put("contentType", c.getContentType() != null ? c.getContentType() : "TEXT");
        entry.put("imageUrl", c.getMetadata() != null
                && c.getMetadata().get("image_url") instanceof String imgUrl ? imgUrl : "");
        return entry;
    }

    // ==================== 工具集构建 ====================

    private Toolkit buildToolkit(AgentContext ctx, RunState state, SandboxExecutor sandboxExecutor,
                                 boolean sandboxEnabled, List<String> allowedCommandPrefixes) {
        Toolkit toolkit = new Toolkit();
        List<String> toolNames = new ArrayList<>();

        // rag_search（引用溯源：chunks 与上下文 [^chunk_N] 编号按同一顺序追加）
        RagSearchTool ragTool = new RagSearchTool(retrievalEngine, searchProperties,
                ctx.getKnowledgeBaseIds(), ctx.getKbSummaryText(),
                ctx.getQuestion(), ctx.getRewrittenQuery(), ctx.getSubQuestions());
        ragTool.setChunksConsumer(chunks -> state.retrievedChunks.addAll(chunks));
        ragTool.setCitationStartIndexSupplier(() -> state.retrievedChunks.size());
        register(toolkit, toolNames, ragTool, state);

        // 内置 time_now
        register(toolkit, toolNames, new TimeTool(), state);

        // MCP 工具（全部注册，运行时由模型自主选择）
        for (McpToolExecutor executor : mcpToolRegistry.listAllExecutors()) {
            register(toolkit, toolNames, new McpToolAdapter(executor), state);
        }

        // tool_reader：运行时发现 MCP + SKILL 工具（展示规范化名，与模型可见名称一致）
        register(toolkit, toolNames,
                new ToolReaderTool(skillLoader, mcpToolRegistry, state.toolNameMapping), state);

        // SKILL 工具（仅注册有执行配置的技能；纯知识型技能通过 tool_reader 激活）
        List<SkillDefinition> skills = skillLoader.getAllSkills();
        int executableSkills = 0;
        for (SkillDefinition def : skills) {
            if (!def.isExecutable()) {
                log.debug("SKILL [{}] 为纯知识型技能，不注册为可调用工具", def.getName());
                continue;
            }
            executableSkills++;
            register(toolkit, toolNames, new SkillTool(def, syncHttpClient, sandboxExecutor,
                    sandboxEnabled, allowedCommandPrefixes), state);
        }

        state.toolNames.addAll(toolNames);
        log.info("AgentScope 工具注册: MCP={}, SKILL={}(可执行 {})，内置={}, 总计={}",
                mcpToolRegistry.size(), skills.size(), executableSkills, 3, toolNames.size());
        return toolkit;
    }

    private void register(Toolkit toolkit, List<String> toolNames, Tool tool, RunState state) {
        try {
            ProjectToolAdapter adapter = new ProjectToolAdapter(tool, result -> onToolResult(result, state));
            String finalName = resolveToolName(tool.name(), state);
            adapter.setExposedName(finalName);
            toolkit.registerTool(adapter);
            toolNames.add(finalName);
        } catch (Exception e) {
            log.warn("工具 [{}] 注册失败，跳过: {}", tool.name(), e.getMessage());
        }
    }

    /**
     * 规范化工具名并消解碰撞：
     * DeepSeek 等厂商严格要求函数名匹配 ^[a-zA-Z0-9_-]+$ 且 ≤64 字符，
     * MCP/SKILL 工具名可能含中文、点号、冒号等非法字符，直接透传会 400；
     * 不同原始名清洗后可能重名（如 a.b 与 a-b 均变为 a_b），追加数字后缀消解。
     * 原始名 → 规范化名 的映射记录在 RunState，供 tool_reader 等展示一致性使用。
     */
    private String resolveToolName(String rawName, RunState state) {
        String base = ProjectToolAdapter.sanitizeToolName(rawName);
        String finalName = base;
        Set<String> usedNames = state.usedToolNames;
        if (!usedNames.add(base)) {
            int counter = 1;
            while (true) {
                String suffix = "_" + (counter++);
                int keep = Math.max(1, ToolNameUtil.MAX_TOOL_NAME_LENGTH - suffix.length());
                finalName = base.length() > keep ? base.substring(0, keep) : base;
                finalName += suffix;
                if (usedNames.add(finalName)) {
                    break;
                }
            }
        }
        state.toolNameMapping.put(rawName, finalName);
        return finalName;
    }

    private void onToolResult(ToolResult result, RunState state) {
        if (CollUtil.isNotEmpty(result.getS3ImageUrls())) {
            state.s3ImageUrls.addAll(result.getS3ImageUrls());
        }
    }

    // ==================== System Prompt 与消息构建 ====================

    /**
     * 构建 System Prompt：基础模板 + 对话目标摘要 + 历史摘要（SYSTEM 消息）+ 前置指令。
     * AgentScope ReActAgent 不允许输入 SYSTEM 角色消息，全部系统内容合并到 sysPrompt。
     */
    private String buildSystemPrompt(AgentContext ctx, List<String> toolNames) {
        String template = templateLoader.load(REACT_SYSTEM_PROMPT_PATH);

        String toolDefs;
        if (CollUtil.isEmpty(toolNames)) {
            toolDefs = NO_TOOLS_TEXT;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("当前可用工具：").append(String.join("、", toolNames)).append("。");
            toolDefs = sb.toString();
        }

        String kbContext = StrUtil.isNotBlank(ctx.getKbContext()) ? ctx.getKbContext() : NO_KB_TEXT;
        String relevanceNote = "";
        boolean hasRagSearch = toolNames.contains("rag_search");
        if (!ctx.isKbRelevant() && StrUtil.isBlank(ctx.getKbContext())) {
            relevanceNote = KB_IRRELEVANT_NOTE;
        } else if (ctx.isKbRelevant() && StrUtil.isBlank(ctx.getKbContext()) && hasRagSearch) {
            relevanceNote = KB_RELEVANT_NOTE;
        }
        String searchPriorityRule = hasRagSearch ? SEARCH_PRIORITY_WITH_RAG : SEARCH_PRIORITY_WITHOUT_RAG;

        String filled = PromptTemplateUtils.fillSlots(template, Map.of(
                "tool_definitions", toolDefs,
                "kb_context", kbContext,
                "kb_relevance_note", relevanceNote,
                "search_priority_rule", searchPriorityRule
        ));
        String sysPrompt = PromptTemplateUtils.cleanupPrompt(filled);

        // 1. 对话目标摘要
        String goalSummary = buildGoalSummary(ctx);
        if (goalSummary != null) {
            sysPrompt = sysPrompt + "\n\n" + goalSummary;
        }

        // 2. 对话历史中的 SYSTEM 消息（摘要等）合并进系统提示词
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            StringBuilder extras = new StringBuilder();
            for (ChatMessage msg : ctx.getHistory()) {
                if (msg.getRole() == ChatMessage.Role.SYSTEM && StrUtil.isNotBlank(msg.getContent())) {
                    extras.append("\n\n").append(msg.getContent());
                }
            }
            if (extras.length() > 0) {
                sysPrompt = sysPrompt + extras;
            }
        }

        // 3. 前置指令（多轮 / 历史图片 / 强制检索）
        StringBuilder reminder = new StringBuilder();
        if (CollUtil.isNotEmpty(ctx.getHistory()) && ctx.getHistory().size() >= 4) {
            reminder.append("\n\n").append(templateLoader.loadSection(AGENT_REMINDER_PATH, "multi_turn"));
        }
        boolean hasHistoryImage = ctx.getHistory() != null && ctx.getHistory().stream()
                .anyMatch(m -> m.getImageUrls() != null && !m.getImageUrls().isEmpty());
        if (hasHistoryImage) {
            reminder.append("\n\n").append(templateLoader.loadSection(AGENT_REMINDER_PATH, "image_history"));
        }
        if (ctx.isKbRelevant() && !ctx.getKnowledgeBaseIds().isEmpty()) {
            reminder.append("\n\n").append(templateLoader.loadSection(AGENT_REMINDER_PATH, "kb_forced"));
        }
        if (reminder.length() > 0) {
            sysPrompt = sysPrompt + "\n\n" + reminder;
        }

        return sysPrompt;
    }

    /**
     * 构建消息列表：历史（USER / ASSISTANT，SYSTEM 已并入 sysPrompt）+ 用户问题
     */
    private List<Msg> buildMessages(AgentContext ctx) {
        List<ChatMessage> messages = new ArrayList<>();

        // 历史消息（SYSTEM 已在 buildSystemPrompt 处理；OBSERVATION 不持久化，防御性跳过）
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            for (ChatMessage msg : ctx.getHistory()) {
                if (msg.getRole() == ChatMessage.Role.SYSTEM
                        || msg.getRole() == ChatMessage.Role.OBSERVATION) {
                    continue;
                }
                messages.add(msg);
            }
        }

        // 用户当前问题（含图片 URL）
        ChatMessage userMsg = ChatMessage.user(ctx.getQuestion());
        if (CollUtil.isNotEmpty(ctx.getImageUrls())) {
            userMsg.setImageUrls(new ArrayList<>(ctx.getImageUrls()));
        }
        messages.add(userMsg);

        return modelFactory.convertMessages(ChatRequest.builder().messages(messages).build());
    }

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
                if (!content.startsWith("Observation:") && !content.startsWith("{\"query\"")
                        && !content.contains("[^chunk_")) {
                    userQuestions.add(content);
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

    // ==================== 模型选择与生成参数 ====================

    /**
     * 选择候选模型：主模型 + fallback（与 RoutingLLMService 相同的分级降级策略）。
     * 图片检测覆盖当前问题图片与历史消息中的图片（旧循环同样检查全部消息）。
     */
    private List<ModelTarget> selectTargets(AgentContext ctx) {
        boolean hasImages = hasImageContent(ctx);
        int thinkingLevel = ctx.getThinkingLevel();
        boolean deepThinking = thinkingLevel > 0;

        List<ModelTarget> candidates;
        if (!hasImages) {
            candidates = selector.selectChatCandidates(deepThinking, false);
        } else {
            String multimodalModelId = defaultModelService.getDefaultModelId("multimodal");
            candidates = selectMultimodalCandidates(multimodalModelId, deepThinking);
        }

        // 过滤无 API Key 的候选，并按健康状态跳过熔断中的模型（与 RoutingLLMService 同语义，
        // 让主 Agent 链路也受跨实例熔断保护：故障模型连续失败后不再被每个请求重复尝试）
        List<ModelTarget> usable = candidates.stream()
                .filter(t -> t.provider() != null && StringUtils.hasText(t.provider().getApiKey()))
                .filter(t -> healthStore.allowCall(t.id()))
                .toList();
        if (usable.isEmpty()) {
            throw new RemoteException("未设置模型/API KEY，或候选模型当前全部不可用（可能正在熔断冷却）");
        }
        return usable.size() > 2 ? usable.subList(0, 2) : usable;
    }

    /** 当前问题或对话历史中是否包含图片 */
    private boolean hasImageContent(AgentContext ctx) {
        if (CollUtil.isNotEmpty(ctx.getImageUrls())) {
            return true;
        }
        return ctx.getHistory() != null && ctx.getHistory().stream()
                .anyMatch(m -> m.getImageUrls() != null && !m.getImageUrls().isEmpty());
    }

    private List<ModelTarget> selectMultimodalCandidates(String preferredId, boolean deepThinking) {
        if (preferredId != null && deepThinking) {
            List<ModelTarget> t = selector.selectChatCandidates(preferredId, true, true);
            if (!t.isEmpty()) {
                return t;
            }
        }
        if (deepThinking) {
            List<ModelTarget> t = preferredId != null
                    ? selector.selectChatCandidates(preferredId, true, true)
                    : selector.selectChatCandidates(true, true);
            if (!t.isEmpty()) {
                return t;
            }
            log.warn("没有同时支持多模态和深度思考的模型，降级为仅多模态模式");
        }
        if (preferredId != null) {
            List<ModelTarget> t = selector.selectChatCandidates(preferredId, false, true);
            if (!t.isEmpty()) {
                return t;
            }
            return selector.selectChatCandidates(false, true);
        }
        return selector.selectChatCandidates(false, true);
    }

    private GenerateOptions buildGenerateOptions(AgentContext ctx, ModelTarget primary) {
        GenerateOptions.Builder builder = GenerateOptions.builder()
                .temperature(0.4);
        int thinkingLevel = ctx.getThinkingLevel();
        if (thinkingLevel > 0 && reasoningRouter != null) {
            Map<String, Object> reasoningParams =
                    reasoningRouter.route(primary.candidate().getModel(), thinkingLevel);
            applyReasoningParams(builder, reasoningParams);
        } else if (thinkingLevel <= 0 && reasoningRouter != null
                && reasoningRouter.usesThinkingSwitch(primary.candidate().getModel())) {
            // DeepSeek 官方文档：思考模式默认开启；非思考模式需显式关闭，
            // 否则模型默认思考，思维链与成本不可控
            builder.additionalBodyParam("thinking", Map.of("type", "disabled"));
        }
        return builder.build();
    }

    private void applyReasoningParams(GenerateOptions.Builder builder, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "reasoning_effort" -> builder.reasoningEffort(String.valueOf(value));
                case "budget_tokens" -> {
                    if (value instanceof Number number) {
                        builder.thinkingBudget(number.intValue());
                    }
                }
                default -> builder.additionalBodyParam(key, value);
            }
        }
    }

    // ==================== 工具方法 ====================

    private Map<String, Object> parseToolArgs(String argsJson) {
        if (StrUtil.isBlank(argsJson)) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = OBJECT_MAPPER.readTree(argsJson);
            if (node.isObject()) {
                Map<String, Object> result = new HashMap<>();
                node.fields().forEachRemaining(e ->
                        result.put(e.getKey(), OBJECT_MAPPER.convertValue(e.getValue(), Object.class)));
                return result;
            }
        } catch (Exception e) {
            log.warn("工具参数 JSON 解析失败: {}", argsJson);
        }
        return Map.of();
    }

    private void pushStep(AgentStep step, StreamCallback callback) {
        try {
            callback.onAgentStep(step);
        } catch (Exception e) {
            log.warn("推送 Agent 步骤失败: {}", e.getMessage());
        }
    }

    private void pushStepsComplete(RunState state, StreamCallback callback) {
        try {
            String json = GSON.toJson(state.steps);
            callback.onAgentStepsComplete(json);
        } catch (Exception e) {
            log.warn("推送 Agent 步骤完成事件失败: {}", e.getMessage());
        }
    }

    private static String drain(StringBuilder sb) {
        if (sb.length() == 0) {
            return "";
        }
        String s = sb.toString();
        sb.setLength(0);
        return s;
    }

    // ==================== Agent 内部节点打点 ====================

    /**
     * 在 Agent 事件回调线程中手动创建链路节点（LLM_CALL / TOOL_CALL）。
     * 事件回调线程没有 ThreadLocal trace 上下文，使用 run() 启动时快照的 traceId。
     *
     * @return 节点 ID；链路未启用或未处于链路中时返回 null
     */
    private String startTraceNode(RunState state, String nodeType, String nodeName) {
        if (!traceProperties.isEnabled() || StrUtil.isBlank(state.traceId)) {
            return null;
        }
        try {
            String nodeId = IdUtil.getSnowflakeNextIdStr();
            state.traceNodeStartTimes.put(nodeId, System.currentTimeMillis());
            traceRecordService.startNode(RagTraceNodeDO.builder()
                    .traceId(state.traceId)
                    .nodeId(nodeId)
                    .parentNodeId(state.agentLoopParentNodeId)
                    // 父节点（Agent循环）存在时为 1 级子节点，前端按 depth 计算缩进层级
                    .depth(state.agentLoopParentNodeId != null ? 1 : 0)
                    .nodeType(nodeType)
                    .nodeName(nodeName)
                    .className(AgentScopeReActExecutor.class.getName())
                    .status(TRACE_STATUS_RUNNING)
                    .startTime(new Date())
                    .build());
            return nodeId;
        } catch (Exception e) {
            log.debug("Agent trace 节点启动失败: {}", e.getMessage());
            return null;
        }
    }

    private void finishTraceNode(RunState state, String nodeId) {
        if (StrUtil.isBlank(nodeId)) {
            return;
        }
        try {
            Long start = state.traceNodeStartTimes.remove(nodeId);
            traceRecordService.finishNode(state.traceId, nodeId, TRACE_STATUS_SUCCESS, null,
                    new Date(), System.currentTimeMillis() - (start != null ? start : System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("Agent trace 节点完成失败: {}", e.getMessage());
        }
    }

    /** 每次运行的本地状态（AgentScope 工具与事件回调在不同线程，需线程安全） */
    private static final class RunState {
        final String taskId;
        final AgentContext ctx;
        /** 启动线程快照的链路 traceId（事件回调线程使用），null=未在链路中 */
        volatile String traceId;
        /** 启动线程快照的父节点（Agent循环）ID */
        volatile String agentLoopParentNodeId;
        /** LLM 调用节点：迭代号 → 节点 ID */
        final Map<Integer, String> modelCallNodeIds = new java.util.concurrent.ConcurrentHashMap<>();
        /** 工具调用节点：toolCallId → 节点 ID */
        final Map<String, String> toolCallNodeIds = new java.util.concurrent.ConcurrentHashMap<>();
        /** 手动节点开始时间：nodeId → startMs */
        final Map<String, Long> traceNodeStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> toolNames = new ArrayList<>();
        /** 已注册的规范化工具名集合（碰撞消解用） */
        final Set<String> usedToolNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** 原始工具名 → 规范化名（供 tool_reader 等与模型可见名称保持一致） */
        final Map<String, String> toolNameMapping = new java.util.concurrent.ConcurrentHashMap<>();
        final List<RetrievedChunk> retrievedChunks = java.util.Collections.synchronizedList(new ArrayList<>());
        final Set<String> s3ImageUrls = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        final List<AgentStep> steps = java.util.Collections.synchronizedList(new ArrayList<>());
        final StringBuilder thinkingBuffer = new StringBuilder();
        final StringBuilder answerBuffer = new StringBuilder();
        final StringBuilder iterationText = new StringBuilder();
        final AtomicBoolean iterationHadToolCall = new AtomicBoolean(false);
        /** 当前迭代已确认进入最终回答流（文本增量直透，避免整段等 ModelCallEnd） */
        final AtomicBoolean incrementalContent = new AtomicBoolean(false);
        final AtomicInteger modelCallCount = new AtomicInteger(0);
        final AtomicBoolean agentEnded = new AtomicBoolean(false);
        final Map<String, PendingToolCall> pendingToolCalls = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, AgentStep> stepByToolCall = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, StringBuilder> toolResultBuffers = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Long> toolCallStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
        volatile Msg finalMsg;

        RunState(String taskId, AgentContext ctx) {
            this.taskId = taskId;
            this.ctx = ctx;
        }
    }

    private static final class PendingToolCall {
        final String name;
        final StringBuilder argsBuffer;

        PendingToolCall(String name, StringBuilder argsBuffer) {
            this.name = name;
            this.argsBuffer = argsBuffer;
        }
    }
}
