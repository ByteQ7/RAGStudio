package com.byteq.ai.ragstudio.infra.chat;

import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * LLM 首包探测组件
 * <p>
 * 将 awaitFirstPacket 拆为独立的 Spring Bean，便于 Spring AOP 采集 TTFT（Time to First Token）trace。
 * <p>
 * 设计意图：
 * - Spring AOP 不拦截类内 self-call，因此 RoutingLLMService 必须依赖外部 Bean 调用此方法
 * - 通过 @RagTraceNode 注解标记为 "LLM_TTFT" 类型节点，在链路追踪中记录首包到达时间
 * - 首包延迟是衡量模型响应速度的关键指标，该组件使其可观测
 * <p>
 * 使用场景：
 * - RoutingLLMService 的流式路由中，在每轮模型尝试后等待首包结果
 * - 监控/告警系统基于 TTFT 数据判断模型健康状态
 */
@Component
public class LlmFirstPacketProbe {

    /**
     * 等待首包探测结果
     * <p>
     * 阻塞等待直到收到首个数据包、超时或发生异常。
     * 一旦收到首个 onContent 或 onThinking 回调，ProbeResult 即为 SUCCESS。
     * <p>
     * 该方法被 @RagTraceNode 增强，在链路追踪中记录等待耗时。
     *
     * @param bridge  ProbeStreamBridge 实例，持有 CompletableFuture<ProbeResult>
     * @param timeout 超时时间
     * @param unit    超时时间单位
     * @return 探测结果：SUCCESS / ERROR / TIMEOUT / NO_CONTENT
     * @throws InterruptedException 等待线程被中断时抛出
     */
    @RagTraceNode(name = "llm-first-packet", type = "LLM_TTFT")
    public ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                          long timeout,
                                                          TimeUnit unit) throws InterruptedException {
        return bridge.awaitFirstPacket(timeout, unit);
    }
}
