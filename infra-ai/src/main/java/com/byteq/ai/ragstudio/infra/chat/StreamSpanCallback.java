package com.byteq.ai.ragstudio.infra.chat;

import com.byteq.ai.ragstudio.framework.trace.RagStreamTraceSupport.StreamSpan;

/**
 * StreamSpan 装饰器 —— 将 StreamSpan 的生命周期与流式回调的终态事件桥接
 * <p>
 * 设计意图：
 * - 让 *-stream-chat trace 节点记录从流式开始到 onComplete / onError 的真实端到端耗时
 * - 继承 ForwardingStreamCallback，通过 onFinish 钩子在流式结束（正常/异常）时自动收尾 span
 * - 提供 onCancel() 方法供取消路径调用，确保 span 不会因未 finish 而成为悬挂节点
 * <p>
 * 使用场景：
 * - RoutingLLMService.streamChat() 中创建 StreamSpan 并包装 callback
 * - 路由层取消流式任务时通过 onCancel() 确保 trace 完整性
 */
public final class StreamSpanCallback extends ForwardingStreamCallback {

    private final StreamSpan span;

    /**
     * 构造 StreamSpanCallback
     *
     * @param delegate 下游的原始 StreamCallback，用于透传内容事件
     * @param span     与本次流式调用关联的 StreamSpan trace 节点
     */
    public StreamSpanCallback(StreamCallback delegate, StreamSpan span) {
        super(delegate);
        this.span = span;
    }

    /**
     * 流式终态收尾：根据成功/失败更新 StreamSpan 状态
     * <p>
     * 由 ForwardingStreamCallback 在 onComplete 或 onError 之后自动触发，
     * 保证 span 的 finish 与回调终态严格对应。
     *
     * @param success 是否成功结束
     * @param error   失败时的异常对象，成功时为 null
     */
    @Override
    protected void onFinish(boolean success, Throwable error) {
        if (success) {
            span.finishSuccess();
        } else {
            span.finishError(error);
        }
    }

    /**
     * 取消时由调用方触发
     * <p>
     * 若 span 仍处于 RUNNING 状态，按取消语义结束 span，
     * 避免 trace 行悬挂导致链路不完整。
     * <p>
     * 同时调用父类的 finishExternally 通知 onFinish 钩子（但不会透传 delegate），
     * 因为下游的 onComplete/onError 已在取消路径中被跳过。
     */
    public void onCancel() {
        span.finishCancelledIfRunning();
        finishExternally(false, null);
    }
}
