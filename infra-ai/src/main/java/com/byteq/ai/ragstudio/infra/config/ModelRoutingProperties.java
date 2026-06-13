package com.byteq.ai.ragstudio.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 模型路由运行时配置属性
 * <p>
 * 管理模型选择策略（熔断器参数）和流式响应参数。
 * 这些配置独立于模型供应商/模型列表（后者由数据库动态管理），
 * 通过 Spring Boot 配置文件注入。
 * </p>
 *
 * <p>配置文件结构（application.yml）：</p>
 * <pre>
 * rag:
 *   model-routing:
 *     selection:
 *       failure-threshold: 2
 *       open-duration-ms: 30000
 *     stream:
 *       message-chunk-size: 5
 *       first-packet-timeout-seconds: 60
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.model-routing")
public class ModelRoutingProperties {

    /**
     * 模型选择策略配置（熔断器参数）
     */
    private Selection selection = new Selection();

    /**
     * 流式响应配置
     */
    private Stream stream = new Stream();

    /**
     * 模型选择策略配置类
     * <p>
     * 配置模型调用过程中的故障转移和熔断策略。
     * 当当前模型调用连续失败达到阈值时，触发熔断保护，暂停使用该模型一段时间，
     * 并自动切换到备用模型，提高系统的可用性和稳定性。
     * </p>
     */
    @Data
    public static class Selection {
        /**
         * 失败阈值：连续调用失败次数达到该值后触发熔断，默认 2
         */
        private Integer failureThreshold = 2;

        /**
         * 熔断器打开持续时间（毫秒）：熔断触发后在此时间内不再尝试失败模型，默认 30000
         */
        private Long openDurationMs = 30000L;
    }

    /**
     * 流式响应配置类
     * <p>
     * 配置 AI 模型流式响应（SSE）的相关参数。
     * </p>
     */
    @Data
    public static class Stream {
        /**
         * 消息分块大小：流式响应中每次向客户端发送的 Token 数量，默认 5
         */
        private Integer messageChunkSize = 5;

        /**
         * 首包超时时间（秒）：流式请求阻塞等待首个数据包到达的最大时间，默认 60。
         * 深度思考（thinking）模式下实际超时时间为此值的 2 倍。
         */
        private Integer firstPacketTimeoutSeconds = 60;
    }
}
