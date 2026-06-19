package com.byteq.ai.ragstudio.rag.trace;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.trace.RagStreamTraceSupport;
import com.byteq.ai.ragstudio.framework.trace.RagTraceContext;
import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨线程流式链路追踪支持实现
 * <p>
 * 解决 {@code @RagTraceNode} 注解 AOP 在流式（Stream）场景中只能拦截到
 * {@code runAsync} 提交阶段的问题。因为流式场景中的 LLM 调用在异步线程中执行，
 * 无法被原始 AOP 切面捕获，因此需要手动创建和管理链路节点。
 * </p>
 * <p>
 * 设计思路：
 * <ul>
 *   <li>通过 {@link #beginStreamNode(String, String)} 在异步线程中手动创建节点</li>
 *   <li>节点启动时自动推入 {@link RagTraceContext} 的节点栈，使后续子节点能识别父节点</li>
 *   <li>返回的 {@link StreamSpan} 提供 {@code detach}、{@code finishSuccess}、
 *       {@code finishError}、{@code finishCancelledIfRunning} 等完整生命周期管理</li>
 *   <li>使用 CAS 机制确保节点的完成操作（finish）和分离操作（detach）只执行一次</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagStreamTraceSupportImpl implements RagStreamTraceSupport {

    /** 运行中状态 */
    private static final String STATUS_RUNNING = "RUNNING";

    /** 成功状态 */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /** 错误状态 */
    private static final String STATUS_ERROR = "ERROR";

    /** 已取消状态 */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** 链路追踪配置 */
    private final RagTraceProperties traceProperties;

    /** 链路追踪记录服务 */
    private final RagTraceRecordService traceRecordService;

    /**
     * 开启一个流式追踪节点
     * <p>
     * 在异步线程中手动创建链路节点记录，用于流式场景下 AOP 无法自动拦截的情况。
     * 节点创建后自动推入 {@link RagTraceContext} 的节点栈，使后续子节点能识别父节点。
     * 若链路追踪未开启或当前线程无 traceId，则返回空操作的 NOOP_SPAN。
     * </p>
     *
     * @param name 节点名称，如 "llm-stream"
     * @param type 节点类型，默认为 "STREAM"
     * @return 流式节点 Span，用于管理节点的分离、完成、失败和取消等生命周期
     */
    @Override
    public StreamSpan beginStreamNode(String name, String type) {
        if (!traceProperties.isEnabled()) {
            return NOOP_SPAN;
        }
        String traceId = RagTraceContext.getTraceId();
        if (StrUtil.isBlank(traceId)) {
            return NOOP_SPAN;
        }

        String nodeId = IdUtil.getSnowflakeNextIdStr();
        String parentNodeId = RagTraceContext.currentNodeId();
        int depth = RagTraceContext.depth();
        long startMillis = System.currentTimeMillis();

        traceRecordService.startNode(RagTraceNodeDO.builder()
                .traceId(traceId)
                .nodeId(nodeId)
                .parentNodeId(parentNodeId)
                .depth(depth)
                .nodeType(StrUtil.blankToDefault(type, "STREAM"))
                .nodeName(name)
                .status(STATUS_RUNNING)
                .startTime(new Date())
                .build());

        // 调用线程上 push，使后续同步子节点（如 first-packet）能识别父节点
        RagTraceContext.pushNode(nodeId);

        return new StreamSpanImpl(traceId, nodeId, startMillis);
    }

    /**
     * 流式节点 Span 实现
     * <p>
     * 管理单个流式节点的完整生命周期，包括分离（detach）、
     * 完成（finishSuccess）、失败（finishError）和取消（finishCancelledIfRunning）。
     * 使用 AtomicBoolean CAS 确保每个操作只执行一次。
     * </p>
     */
    private final class StreamSpanImpl implements StreamSpan {
        private final String traceId;
        private final String nodeId;
        private final long startMillis;
        private final AtomicBoolean detached = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        StreamSpanImpl(String traceId, String nodeId, long startMillis) {
            this.traceId = traceId;
            this.nodeId = nodeId;
            this.startMillis = startMillis;
        }

        /**
         * 将当前节点从上下文节点栈中分离
         * <p>
         * 仅当栈顶节点为本节点时才执行出栈操作，防止并发节点错乱。
         * CAS 确保只执行一次。
         * </p>
         */
        @Override
        public void detach() {
            if (!detached.compareAndSet(false, true)) {
                return;
            }
            // 仅当栈顶为本节点才 pop，防止与并发节点错乱
            if (nodeId.equals(RagTraceContext.currentNodeId())) {
                RagTraceContext.popNode();
            }
        }

        /**
         * 标记节点为成功完成
         * <p>
         * 记录节点的成功状态、结束时间和耗时。CAS 确保只执行一次。
         * </p>
         */
        @Override
        public void finishSuccess() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                traceRecordService.finishNode(traceId, nodeId, STATUS_SUCCESS, null,
                        new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishSuccess 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }

        /**
         * 标记节点为失败
         * <p>
         * 记录节点的错误状态、异常信息和耗时。异常信息会被截断。
         * CAS 确保只执行一次。
         * </p>
         *
         * @param error 异常信息
         */
        @Override
        public void finishError(Throwable error) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                traceRecordService.finishNode(traceId, nodeId, STATUS_ERROR,
                        truncateError(error), new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishError 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }

        /**
         * 如果节点还在运行中则标记为取消
         * <p>
         * 当流式任务被用户主动取消时调用，仅当节点尚未完成时才标记为取消。
         * CAS 确保只执行一次。
         * </p>
         */
        @Override
        public void finishCancelledIfRunning() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                traceRecordService.finishNode(traceId, nodeId, STATUS_CANCELLED, null,
                        new Date(), System.currentTimeMillis() - startMillis);
            } catch (Exception e) {
                log.warn("stream trace finishCancelled 失败，traceId：{}，nodeId：{}", traceId, nodeId, e);
            }
        }
    }

    /**
     * 截断异常信息
     * <p>
     * 将异常信息格式化为"异常类名: 异常消息"的形式，按配置的最大长度截断。
     * </p>
     *
     * @param throwable 异常对象
     * @return 截断后的异常信息字符串
     */
    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": "
                + StrUtil.blankToDefault(throwable.getMessage(), "");
        int max = traceProperties.getMaxErrorLength();
        return message.length() <= max ? message : message.substring(0, max);
    }
}
