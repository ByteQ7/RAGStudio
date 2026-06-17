package com.byteq.ai.ragstudio.infra.chat;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 透传式 StreamCallback 装饰器抽象基类
 * <p>
 * 通过装饰器模式，在透传 StreamCallback 事件的同时，提供流式终态的钩子机制。
 * <p>
 * 设计意图：
 * - onContent / onThinking 直接透传给 delegate，不干预业务内容
 * - onComplete / onError 透传后通过 CAS-once 保证只触发一次 onFinish 回调
 * - onFinish 由子类实现，用于在流式结束时执行 trace 收尾、计量上报等逻辑
 * - 提供 onFirstContent 钩子，子类可覆写以记录用户感知的首包时间（TTFT）
 * <p>
 * 模板方法：
 * - onContent()：final 方法，首次调用时触发 onFirstContent() 钩子
 * - onComplete() / onError()：final 方法，透传后触发 onFinish()
 * - finishExternally()：供外部路径（如 cancel）绕过 delegate，直接触发 onFinish()
 * <p>
 * 线程安全：
 * - 使用 AtomicBoolean 确保 onFinish 和 onFirstContent 钩子各只触发一次
 * - 不假设 delegate 的线程安全性，由具体实现保证
 */
@Slf4j
public abstract class ForwardingStreamCallback implements StreamCallback {

    /** 下游真实的 StreamCallback 实例 */
    private final StreamCallback delegate;
    /** 终态标记：确保 onFinish 只触发一次 */
    private final AtomicBoolean finished = new AtomicBoolean(false);
    /** 首包标记：确保 onFirstContent 只触发一次 */
    private final AtomicBoolean firstContentSeen = new AtomicBoolean(false);

    /**
     * 构造 ForwardingStreamCallback
     *
     * @param delegate 下游真实的 StreamCallback，所有内容事件将透传至此
     */
    protected ForwardingStreamCallback(StreamCallback delegate) {
        this.delegate = delegate;
    }

    /**
     * 接收增量内容（final）
     * <p>
     * 首次收到 onContent 时触发 onFirstContent() 钩子，
     * 之后直接透传 delegate.onContent()。
     */
    @Override
    public final void onContent(String content) {
        // 首次收到内容 → 触发首包钩子，用于采集 TTFT
        if (firstContentSeen.compareAndSet(false, true)) {
            try {
                onFirstContent();
            } catch (Throwable ex) {
                log.warn("onFirstContent hook failed", ex);
            }
        }
        delegate.onContent(content);
    }

    /**
     * 接收思考过程增量内容（final）
     * <p>
     * 直接透传 delegate。
     */
    @Override
    public final void onThinking(String content) {
        delegate.onThinking(content);
    }

    /**
     * 首包钩子
     * <p>
     * 流式响应到达「第一个 onContent」时触发一次，
     * 子类可覆写以记录用户感知的 TTFT（Time to First Token）。
     * 默认空实现。
     */
    protected void onFirstContent() {
    }

    /**
     * 流式正常结束（final）
     * <p>
     * 先透传 delegate.onComplete()，再触发 onFinish(true, null)。
     * 即使 delegate.onComplete() 抛出异常，onFinish 仍会被执行。
     */
    @Override
    public final void onComplete() {
        try {
            delegate.onComplete();
        } finally {
            finishOnce(true, null);
        }
    }

    /**
     * 流式异常结束（final）
     * <p>
     * 先透传 delegate.onError(error)，再触发 onFinish(false, error)。
     * 即使 delegate.onError() 抛出异常，onFinish 仍会被执行。
     */
    @Override
    public final void onError(Throwable error) {
        try {
            delegate.onError(error);
        } finally {
            finishOnce(false, error);
        }
    }

    /**
     * 外部路径触发收尾（final）
     * <p>
     * 适用于取消（cancel）等场景：
     * 不再透传 delegate 的 onComplete/onError，
     * 但需要触发 onFinish 以保证 trace 链路的完整。
     *
     * @param success 是否成功
     * @param error   异常信息（成功时为 null）
     */
    protected final void finishExternally(boolean success, Throwable error) {
        finishOnce(success, error);
    }

    /**
     * CAS-once 收尾：确保 onFinish 只执行一次
     */
    private void finishOnce(boolean success, Throwable error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        onFinish(success, error);
    }

    /**
     * 流式终态收尾回调（子类实现）
     * <p>
     * 在 onComplete / onError / cancel 时确保只被调用一次。
     * 典型用途：关闭 trace span、上报调用计量、释放资源。
     *
     * @param success 是否成功结束
     * @param error   失败时的异常，成功为 null
     */
    protected abstract void onFinish(boolean success, Throwable error);
}
