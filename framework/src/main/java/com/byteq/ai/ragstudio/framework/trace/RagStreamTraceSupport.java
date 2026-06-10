package com.byteq.ai.ragstudio.framework.trace;

/**
 * 跨线程流式节点的 Trace 支持接口
 *
 * <p>解决 {@code @RagTraceNode} AOP 切面在 SSE 流式响应场景下只能检测到
 * 任务提交（如 {@code CompletableFuture.runAsync}）的问题。</p>
 *
 * <p>背景：</p>
 * <ul>
 *   <li>同步部分（HTTP 请求接收、首包阻塞等待）在调用线程中执行</li>
 *   <li>真正的 SSE 读循环在线程池的 worker 线程中执行</li>
 *   <li>AOP 切面无法跨越这两个不同线程追踪同一节点</li>
 * </ul>
 *
 * <p>解决方案：</p>
 * 通过本接口允许调用线程开启一个跨线程节点，
 * 由 SSE 的 {@code onComplete} / {@code onError} 回调在异步线程上完成节点结束标记。
 *
 * @see RagTraceNode
 */
public interface RagStreamTraceSupport {

    /**
     * 在调用线程开启一个流式节点
     * <p>执行以下操作：</p>
     * <ul>
     *   <li>插入 RUNNING 行到 Trace 数据库</li>
     *   <li>把 nodeId push 进当前线程的 {@link RagTraceContext#pushNode(String)}，
     *       以便嵌套的同步子节点（如 first-packet 检测）能识别父节点</li>
     * </ul>
     *
     * <p>注意：返回的 {@link StreamSpan} 必须在调用线程同步部分结束前调用
     * {@link StreamSpan#detach()} 将 nodeId 弹出栈，
     * 而 {@code finish} 方法在异步线程中触发。</p>
     *
     * @param name 节点名称
     * @param type 节点类型
     * @return 流式节点控制句柄，用于在适当时机结束节点
     */
    StreamSpan beginStreamNode(String name, String type);

    /**
     * 流式节点控制句柄
     *
     * <p>提供了跨线程控制节点生命周期的方法：
     * <ul>
     *   <li>{@link #detach()}：在同步线程结束时将节点 ID 从栈中弹出</li>
     *   <li>{@link #finishSuccess()}：在异步线程成功完成时调用</li>
     *   <li>{@link #finishError(Throwable)}：在异步线程异常时调用</li>
     *   <li>{@link #finishCancelledIfRunning()}：取消路径，清理未完成的节点</li>
     * </ul>
     */
    interface StreamSpan {

        /**
         * 分离节点栈
         * <p>调用线程同步部分结束时调用，将 nodeId 从 {@link RagTraceContext#popNode()} 弹出。
         * 不会 finish 节点，finish 由异步线程负责。</p>
         */
        void detach();

        /**
         * 标记节点执行成功
         * <p>在异步线程的 {@code onComplete} 回调中调用，使用 CAS 机制保证幂等。</p>
         */
        void finishSuccess();

        /**
         * 标记节点执行失败
         * <p>在异步线程的 {@code onError} 回调中调用，使用 CAS 机制保证幂等。</p>
         *
         * @param error 导致失败的异常
         */
        void finishError(Throwable error);

        /**
         * 取消路径清理
         * <p>如果节点尚未 finish，则按取消语义收尾，避免 RUNNING 状态悬挂。</p>
         */
        void finishCancelledIfRunning();
    }

    /**
     * 空实现（NO-OP）
     * <p>在不启用 Trace 追踪时使用，所有方法均为空操作，避免空指针检查。</p>
     */
    StreamSpan NOOP_SPAN = new StreamSpan() {
        @Override public void detach() {}
        @Override public void finishSuccess() {}
        @Override public void finishError(Throwable error) {}
        @Override public void finishCancelledIfRunning() {}
    };
}
