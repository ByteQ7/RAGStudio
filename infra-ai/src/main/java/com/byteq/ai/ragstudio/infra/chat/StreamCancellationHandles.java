package com.byteq.ai.ragstudio.infra.chat;

import lombok.NoArgsConstructor;
import okhttp3.Call;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StreamCancellationHandle 工具类
 * <p>
 * 用于构建常见的取消句柄，统一幂等取消语义。
 * <p>
 * 设计意图：
 * - 集中管理各种取消句柄的创建，避免重复实现幂等取消逻辑
 * - 提供 NOOP（空操作）句柄用于无法取消的场景
 * - fromOkHttp 将 OkHttp Call 包装为带幂等保护的取消句柄
 * <p>
 * 使用场景：
 * - StreamAsyncExecutor.submit() 中使用 fromOkHttp 包装异步任务
 * - 非流式场景或任务已结束时使用 noop() 返回空句柄
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class StreamCancellationHandles {

    private static final StreamCancellationHandle NOOP = () -> {
    };

    /**
     * 返回一个空操作取消句柄
     * <p>
     * 适用于以下场景：
     * - 非流式调用不需要取消
     * - 流式任务提交失败时的兜底返回值
     * - 任务已完成后的占位符句柄
     *
     * @return 空操作句柄，调用 cancel() 不执行任何操作
     */
    public static StreamCancellationHandle noop() {
        return NOOP;
    }

    /**
     * 基于 OkHttp Call 创建幂等取消句柄
     * <p>
     * 包装 OkHttp 的 Call.cancel() 与 AtomicBoolean 取消标志，
     * 确保取消操作只执行一次（幂等），避免重复取消引发竞态问题。
     * <p>
     * 取消时会依次执行：
     * 1. 设置 cancelled 标志为 true，通知流式读取循环退出
     * 2. 调用 Call.cancel() 中断 HTTP 连接
     *
     * @param call     OkHttp Call 对象，可为 null
     * @param cancelled 取消标志位，流式循环通过检查此标志决定是否退出
     * @return 幂等的取消句柄
     */
    public static StreamCancellationHandle fromOkHttp(Call call, AtomicBoolean cancelled) {
        return new OkHttpCancellationHandle(call, cancelled);
    }

    /**
     * OkHttp 取消句柄的内部实现
     * <p>
     * 使用 AtomicBoolean once 保证 cancel() 方法只执行一次：
     * - 首次调用：设置取消标志 + 取消 HTTP 调用
     * - 重复调用：直接忽略，防止 ConcurrentModification 或重复关闭
     */
    private static final class OkHttpCancellationHandle implements StreamCancellationHandle {

        private final Call call;
        private final AtomicBoolean cancelled;
        private final AtomicBoolean once = new AtomicBoolean(false);

        private OkHttpCancellationHandle(Call call, AtomicBoolean cancelled) {
            this.call = call;
            this.cancelled = cancelled;
        }

        @Override
        public void cancel() {
            // CAS 确保取消逻辑只执行一次
            if (!once.compareAndSet(false, true)) {
                return;
            }
            // 设置取消标志，通知流式读取线程退出
            if (cancelled != null) {
                cancelled.set(true);
            }
            // 取消 HTTP 连接，中断正在进行的 SSE 请求
            if (call != null) {
                call.cancel();
            }
        }
    }
}
