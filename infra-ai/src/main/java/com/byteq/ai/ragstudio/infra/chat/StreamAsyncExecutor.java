package com.byteq.ai.ragstudio.infra.chat;

import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 流式任务异步执行器
 * <p>
 * 统一处理 SSE 流式任务的线程池提交、线程池满载兜底和幂等取消句柄的构建。
 * <p>
 * 设计意图：
 * - 将流式 HTTP 调用（阻塞式 OkHttp execute）提交到独立的 modelStreamExecutor 线程池，
 *   避免阻塞 Netty 或 Tomcat 的 I/O 线程
 * - 当线程池满载时（RejectedExecutionException），立即取消 HTTP 调用并通知回调异常，
 *   避免请求无响应地悬空
 * - 统一构建带幂等保护的 StreamCancellationHandle，免除调用方重复实现取消逻辑
 * <p>
 * 使用场景：
 * - AbstractOpenAIStyleChatClient.doStreamChat() 中提交流式读取任务
 * - 所有继承 AbstractOpenAIStyleChatClient 的 ChatClient 实现均通过此执行器管理异步任务
 */
@Slf4j
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamAsyncExecutor {

    /** 线程池满载时的错误提示消息 */
    private static final String STREAM_BUSY_MESSAGE = "流式线程池繁忙";

    /**
     * 提交流式任务到异步线程池执行
     * <p>
     * 流程：
     * 1. 创建 AtomicBoolean 取消标志
     * 2. 通过 CompletableFuture.runAsync 将流式读取任务提交到 executor
     * 3. 注册 exceptionally 回调，捕获异步任务中的未处理异常
     * 4. 若线程池拒绝提交，立即取消 HTTP 调用、回调 onError、返回 NOOP 句柄
     * <p>
     * 线程安全：
     * - exceptionally 回调先检查 cancelled 标志再回调 onError，
     *   避免取消后的剩余异常被误传给下游
     *
     * @param executor   异步执行的线程池
     * @param call       OkHttp Call 对象，用于取消 HTTP 连接
     * @param callback   流式回调接口，接收内容事件和异常通知
     * @param streamTask 流式读取任务，接收 AtomicBoolean 作为取消标志
     * @return 幂等的流式取消句柄
     */
    static StreamCancellationHandle submit(Executor executor,
                                           Call call,
                                           StreamCallback callback,
                                           Consumer<AtomicBoolean> streamTask) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        try {
            // 异步提交流式读取任务到独立的线程池
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> streamTask.accept(cancelled), executor);
            // 异步任务异常处理：非取消导致的异常才通知下游
            future.exceptionally(ex -> {
                if (!cancelled.get()) {
                    log.warn("流式异步任务异常", ex);
                    callback.onError(ex);
                }
                return null;
            });
        } catch (RejectedExecutionException ex) {
            // 线程池满载：立即取消 HTTP 连接并通知调用方
            call.cancel();
            callback.onError(new ModelClientException(STREAM_BUSY_MESSAGE, ModelClientErrorType.SERVER_ERROR, null, ex));
            return StreamCancellationHandles.noop();
        }
        // 返回绑定 OkHttp Call 的幂等取消句柄
        return StreamCancellationHandles.fromOkHttp(call, cancelled);
    }
}
