package com.byteq.ai.ragstudio.infra.chat;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * StreamCancellationHandle 工具类
 * <p>
 * 提供常见的取消句柄构建方法，统一幂等取消语义。
 * <p>
 * 注意：流式取消现由 LangChain4jStreamBridge 通过 AtomicBoolean 终止标记实现。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
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
}
