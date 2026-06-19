package com.byteq.ai.ragstudio.rag.trace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.infra.chat.ForwardingStreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceRunDO;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.function.Consumer;

/**
 * 流式对话链路追踪包装器
 * <p>
 * 为流式对话（SSE Chat）提供全链路追踪能力。在对话执行前后自动记录链路运行信息（Run），
 * 并通过装饰器模式包装 StreamCallback，在回调中自动记录首包耗时（TTFT）和完成状态。
 * </p>
 * <p>
 * 链路追踪设计思路：
 * <ul>
 *   <li><b>运行记录（Run）</b>：一次完整的 RAG 对话对应一次 Run，记录整体的开始、结束和状态</li>
 *   <li><b>节点记录（Node）</b>：对话过程中的每个步骤（改写、意图、检索、LLM 等）对应一个 Node</li>
 *   <li><b>首包耗时（TTFT）</b>：Time To First Token，从请求发起到推送第一个字符的耗时</li>
 *   <li><b>ThreadLocal 隔离</b>：同步阶段使用 ThreadLocal 传递 traceId，异步线程通过 TransmittableThreadLocal 传递</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamChatTraceRunner {

    private static final String ENTRY_METHOD = "RAGChatService#streamChat";

    private static final String TRACE_NAME = "rag-stream-chat";

    private static final String STATUS_RUNNING = "RUNNING";

    private static final String STATUS_SUCCESS = "SUCCESS";

    private static final String STATUS_ERROR = "ERROR";

    private static final String USER_TTFT_NODE_NAME = "user-first-packet";

    private static final String USER_TTFT_NODE_TYPE = "USER_TTFT";

    private final RagTraceProperties traceProperties;

    private final RagTraceRecordService traceRecordService;

    /**
     * 执行带链路追踪的流式对话
     * <p>
     * 如果启用了链路追踪，则在对话前后自动记录 Run 的开始和完成，
     * 并在首次内容推送时自动记录首包耗时节点（USER_TTFT）。
     * 同步阶段的异常会通过 callback.onError 触发收尾逻辑。
     * </p>
     *
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param taskId         任务 ID
     * @param callback       原始流式回调
     * @param businessLogic  接收 trace 增强后的 callback 并触发 pipeline 执行
     */
    public void run(String question,
                    String conversationId,
                    String taskId,
                    StreamCallback callback,
                    Consumer<StreamCallback> businessLogic) {
        // 未启用 trace 时直接执行业务逻辑，不产生任何追踪记录
        if (!traceProperties.isEnabled()) {
            runWithoutTrace(conversationId, taskId, callback, businessLogic);
            return;
        }

        // 生成全局唯一的 traceId（雪花算法），作为本次请求的追踪标识
        String traceId = IdUtil.getSnowflakeNextIdStr();
        long startMillis = System.currentTimeMillis();
        // 向数据库写入一条运行中（RUNNING）的链路运行记录
        traceRecordService.startRun(RagTraceRunDO.builder()
                .traceId(traceId)
                .traceName(TRACE_NAME)
                .entryMethod(ENTRY_METHOD)
                .conversationId(conversationId)
                .taskId(taskId)
                .userId(UserContext.getUserId())
                .status(STATUS_RUNNING)
                .startTime(new Date())
                .extraData(StrUtil.format("{\"questionLength\":{}}", StrUtil.length(question)))
                .build());

        // 用 ForwardingStreamCallback 装饰原始 callback，在关键回调点插入 trace 逻辑
        Date runStartTime = new Date(startMillis);
        StreamCallback traceAwareCallback = new ForwardingStreamCallback(callback) {
            // 首次内容推送时记录用户感知的首包耗时（TTFT）
            @Override
            protected void onFirstContent() {
                recordUserTtft(traceId, runStartTime, startMillis);
            }

            // 流结束时更新链路运行记录为成功或失败
            @Override
            protected void onFinish(boolean success, Throwable error) {
                finishRun(traceId, success, error, startMillis);
            }
        };

        // 在调用线程上设置 trace 上下文，使后续 @RagTraceNode 注解能感知当前链路
        RagTraceContext.setTraceId(traceId);
        RagTraceContext.setTaskId(taskId);
        try {
            // 执行业务逻辑（pipeline），传入增强后的 callback
            businessLogic.accept(traceAwareCallback);
        } catch (Throwable ex) {
            // 同步阶段抛出异常时，通过 callback.onError 触发收尾
            // 复用 ForwardingStreamCallback 内部的 CAS 逻辑，防止与异步线程的终态重复收尾
            log.warn("执行流式对话失败（同步阶段），会话ID：{}，任务ID：{}", conversationId, taskId, ex);
            try {
                traceAwareCallback.onError(ex);
            } catch (Throwable ignored) {
                // 确保 trace 不会因为 callback.onError 自身异常而悬挂
            }
        } finally {
            // 同步阶段结束立即清理当前线程的 ThreadLocal
            // 异步线程（如 SSE 读循环）依赖的是 TTL 在 submit 时快照的副本，不依赖此线程的上下文
            RagTraceContext.clear();
        }
    }

    /**
     * 记录用户感知首包耗时（TTFT）
     * <p>
     * TTFT（Time To First Token）是从 run 开始（pipeline 入口）到推送给前端第一个字符的耗时。
     * 该指标反映了完整链路的前置开销，包括：路由、查询改写、意图解析、检索、LLM 首包等阶段。
     * </p>
     *
     * @param traceId      链路追踪 ID
     * @param runStartTime 运行开始时间
     * @param startMillis  开始时间戳（毫秒）
     */
    private void recordUserTtft(String traceId, Date runStartTime, long startMillis) {
        // 计算从请求开始到首次内容推送的总耗时
        long now = System.currentTimeMillis();
        long durationMs = Math.max(0, now - startMillis);
        // 生成节点 ID，写入一条立即完成（start + finish 连续提交）的 TTFT 节点
        // trace DB 操作已异步化，不会阻塞模型流线程
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .depth(0)
                .nodeType(USER_TTFT_NODE_TYPE)
                .nodeName(USER_TTFT_NODE_NAME)
                .status(STATUS_RUNNING)
                .startTime(runStartTime)
                .build());
        traceRecordService.finishNode(traceId, nodeId, STATUS_SUCCESS, null, new Date(now), durationMs);
    }

    /**
     * 完成链路运行记录
     * <p>
     * 更新运行记录的结束时间、最终状态和总耗时。
     * 异常信息会被截断以防止过长的错误消息影响数据库存储。
     * </p>
     *
     * @param traceId    链路追踪 ID
     * @param success    是否成功完成
     * @param error      异常信息（失败时提供）
     * @param startMillis 开始时间戳
     */
    private void finishRun(String traceId, boolean success, Throwable error, long startMillis) {
        // 结束链路：将 run 记录从 RUNNING 更新为 SUCCESS 或 ERROR，附带总耗时
        try {
            traceRecordService.finishRun(
                    traceId,
                    success ? STATUS_SUCCESS : STATUS_ERROR,
                    success ? null : truncateError(error),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
        } catch (Exception e) {
            // finishRun 自身异常不影响业务，仅记录日志
            log.warn("finishRun 失败，traceId：{}", traceId, e);
        }
    }

    /**
     * 不开启链路追踪时直接执行业务逻辑
     * <p>
     * 当 trace 功能被禁用时使用此方法直接调用业务逻辑，
     * 不记录任何链路数据，以减少不必要的开销。
     * </p>
     */
    private void runWithoutTrace(String conversationId,
                                 String taskId,
                                 StreamCallback callback,
                                 Consumer<StreamCallback> businessLogic) {
        // 不走链路追踪，直接用原始 callback 执行业务
        try {
            businessLogic.accept(callback);
        } catch (Throwable ex) {
            log.warn("执行流式对话失败，会话ID：{}，任务ID：{}", conversationId, taskId, ex);
            try {
                callback.onError(ex);
            } catch (Throwable ignored) {
                // 防止 callback.onError 自身异常导致静默丢失
            }
        }
    }

    // 截断异常信息：格式化为"异常类名: 异常消息"并按配置的最大长度截断，防止落库过大
    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        // 格式化为 "异常类名: 异常消息"，按配置截断长度防止落库过大
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        int max = traceProperties.getMaxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }
}
