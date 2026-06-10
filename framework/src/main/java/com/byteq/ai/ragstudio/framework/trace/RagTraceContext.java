package com.byteq.ai.ragstudio.framework.trace;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG Trace 追踪上下文
 *
 * <p>基于 Alibaba TransmittableThreadLocal（TTL）实现在异步线程池中透传
 * Trace ID、Task ID 和节点调用栈，用于构建 RAG 链路的完整调用拓扑。</p>
 *
 * <p>核心数据：</p>
 * <ul>
 *   <li>{@link #TRACE_ID}：一次完整 RAG 请求的唯一追踪标识</li>
 *   <li>{@link #TASK_ID}：当前会话任务的唯一标识</li>
 *   <li>{@link #NODE_STACK}：调用节点栈，记录当前请求的方法调用链层级</li>
 * </ul>
 *
 * <p>重要说明：父线程向子线程传递 {@link #NODE_STACK} 时必须进行深拷贝。
 * TTL 默认的 {@code copy()} 方法返回父值的引用，在并发子任务场景下，
 * 多个子线程会共用同一个 {@link Deque} 实例，互相 push/pop 会导致
 * 父子节点 ID 串挂，Trace 层级紊乱。</p>
 */
public final class RagTraceContext {

    /**
     * 链路追踪 ID，标识一次完整的 RAG 请求
     */
    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();

    /**
     * 会话任务 ID，标识当前用户的会话任务
     */
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();

    /**
     * 调用节点栈
     * <p>使用栈结构记录当前请求的方法调用链，支持嵌套节点追踪。
     * 创建 TTL 时重写 {@code copy()} 方法实现父→子的深拷贝，
     * 避免多个子线程共用同一栈实例导致数据错乱。</p>
     */
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>() {
        @Override
        public Deque<String> copy(Deque<String> parentValue) {
            return parentValue == null ? null : new ArrayDeque<>(parentValue);
        }
    };

    private RagTraceContext() {
    }

    /**
     * 获取当前线程的链路追踪 ID
     *
     * @return 追踪 ID 字符串，可能为 null（未初始化）
     */
    public static String getTraceId() {
        return TRACE_ID.get();
    }

    /**
     * 设置当前线程的链路追踪 ID
     *
     * @param traceId 追踪 ID 字符串
     */
    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    /**
     * 获取当前线程的会话任务 ID
     *
     * @return 任务 ID 字符串，可能为 null（未初始化）
     */
    public static String getTaskId() {
        return TASK_ID.get();
    }

    /**
     * 设置当前线程的会话任务 ID
     *
     * @param taskId 任务 ID 字符串
     */
    public static void setTaskId(String taskId) {
        TASK_ID.set(taskId);
    }

    /**
     * 获取当前调用节点栈的深度
     *
     * @return 节点栈深度，如果未初始化则返回 0
     */
    public static int depth() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    /**
     * 获取当前正在执行的节点 ID（栈顶元素）
     *
     * @return 当前节点 ID，如果栈为空则返回 null
     */
    public static String currentNodeId() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? null : stack.peek();
    }

    /**
     * 推送一个节点 ID 到调用栈中
     * <p>在进入一个 RAG 追踪节点时调用，表示开始执行该节点。</p>
     *
     * @param nodeId 节点唯一标识
     */
    public static void pushNode(String nodeId) {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        stack.push(nodeId);
    }

    /**
     * 从调用栈中弹出当前节点 ID
     * <p>在退出一个 RAG 追踪节点时调用，表示该节点执行完毕。
     * 如果弹出后栈为空，则自动移除 TTL 中的栈实例。</p>
     */
    public static void popNode() {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.pop();
        if (stack.isEmpty()) {
            NODE_STACK.remove();
        }
    }

    /**
     * 清理当前线程的所有 Trace 上下文
     * <p>在请求结束时调用，清理 Trace ID、Task ID 和节点栈，
     * 避免线程池复用线程时导致上下文污染。</p>
     */
    public static void clear() {
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
    }
}
