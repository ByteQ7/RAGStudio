package com.byteq.ai.ragstudio.infra.chat;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器
 * <p>
 * 代理 StreamCallback，在流式请求启动后阻塞等待首个数据包（onContent 或 onThinking），
 * 用于实现路由层的"首包超时探测 + 自动切换模型"机制。
 * <p>
 * 设计意图：
 * - 流式路由层需要判断"模型是否真正开始输出"，而非仅仅"TCP 连接是否建立"
 * - 在收到首包之前，所有回调事件被缓冲在内部队列中，不向下游透传
 * - 首包到达（SUCCESS）后提交缓冲，后续事件直通下游，零额外开销
 * - 若超时未收到首包，探测结果为 TIMEOUT，路由层据此切换模型
 * <p>
 * 线程安全：
 * - 通过 synchronized(lock) + volatile committed 保证首包到达前的缓冲安全
 * - committed 切换后，后续事件无需加锁，直接透传
 */
public final class ProbeStreamBridge implements StreamCallback {

    /** 下游真实的 StreamCallback */
    private final StreamCallback downstream;
    /** 首包探测结果 Future，由 onContent/onThinking/onComplete/onError 完成 */
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    /** 缓冲/提交切换的锁 */
    private final Object lock = new Object();
    /** 首包到达前暂存的事件缓冲队列 */
    private final List<Runnable> buffer = new ArrayList<>();
    /** 是否已提交（首包已到达，缓冲已释放） */
    private volatile boolean committed;

    /**
     * 构造 ProbeStreamBridge
     *
     * @param downstream 下游真实的流式回调，首包到达后所有事件将透传至此
     */
    ProbeStreamBridge(StreamCallback downstream) {
        this.downstream = downstream;
    }

    /**
     * 接收增量内容
     * <p>
     * 首个 onContent 会完成探测 Future（设为 SUCCESS），
     * 首包到达前的事件被缓冲，到达后直通下游。
     */
    @Override
    public void onContent(String content) {
        // 首个内容片段到达 → 完成探测 Future，标记首包成功
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }

    /**
     * 接收思考过程增量内容
     * <p>
     * 如果模型先输出思考内容（如 DeepSeek-R1 的 reasoning_content），
     * 此回调同样视为"首包到达"，完成探测 Future。
     */
    @Override
    public void onThinking(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    /**
     * 流式结束（无内容到达）
     * <p>
     * 若模型直接结束而未输出任何内容，标记为 NO_CONTENT。
     * 路由层据此判断模型不可用。
     */
    @Override
    public void onComplete() {
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    /**
     * 流式异常
     * <p>
     * 连接建立后发生的异常（如协议错误、模型内部错误），标记为 ERROR。
     */
    @Override
    public void onError(Throwable t) {
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    /**
     * 阻塞等待首包探测结果
     * <p>
     * 等待 onContent/onThinking/onComplete/onError 中任一事件完成 Future。
     * 若探测结果为 SUCCESS，自动提交缓冲队列，使已缓冲的事件向下游放行。
     * <p>
     * 超时返回 TIMEOUT，路由层据此关闭当前连接并尝试下一个模型。
     *
     * @param timeout 超时时间
     * @param unit    超时时间单位
     * @return 探测结果枚举
     * @throws InterruptedException 等待线程被中断时抛出
     */
    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        ProbeResult result;
        try {
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        // 首包成功 → 释放缓冲，后续事件直通下游
        if (result.isSuccess()) {
            commit();
        }
        return result;
    }

    /**
     * 提交缓冲队列
     * <p>
     * 将首包到达前暂存的所有事件一次性放行到下游。
     * 使用 synchronized 保证与 bufferOrDispatch 的互斥，
     * 确保缓冲事件不会丢失也不会重复执行。
     */
    private void commit() {
        synchronized (lock) {
            if (committed) {
                return;
            }
            committed = true;
            buffer.forEach(Runnable::run);
        }
    }

    /**
     * 缓冲或直接派发
     * <p>
     * 未提交时：将事件加入缓冲队列
     * 已提交时：直接执行，无需加锁（committed 保证可见性）
     */
    private void bufferOrDispatch(Runnable action) {
        boolean dispatchNow;
        synchronized (lock) {
            dispatchNow = committed;
            if (!dispatchNow) {
                buffer.add(action);
            }
        }
        if (dispatchNow) {
            action.run();
        }
    }

    /**
     * 探测结果
     * <p>
     * 封装首包探测的四种可能结果：
     * - SUCCESS：已收到首个内容/思考包
     * - ERROR：流式请求发生异常
     * - TIMEOUT：等待超时
     * - NO_CONTENT：模型直接完成，未输出任何内容
     */
    @Getter
    public static class ProbeResult {

        enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        private final Type type;
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        static ProbeResult error(Throwable t) {
            return new ProbeResult(Type.ERROR, t);
        }

        static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
