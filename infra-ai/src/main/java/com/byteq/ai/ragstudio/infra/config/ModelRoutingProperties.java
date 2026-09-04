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
 *       max-open-duration-ms: 600000
 *     stream:
 *       message-chunk-size: 5
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
     * HTTP 客户端配置
     */
    private Http http = new Http();

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
         * 熔断冷却基础时长（毫秒）：指数退避的基准值——第 1 轮熔断冷却即该值，
         * 之后每轮翻倍（30s → 60s → 120s → …），默认 30000
         */
        private Long openDurationMs = 30000L;

        /**
         * 熔断冷却时长上限（毫秒）：指数退避的封顶值，冷却 = min(base × 2^(轮数-1), 上限)，默认 600000（10 分钟）。
         * 模型恢复（markSuccess）后轮数清零，下次熔断重新从 base 起步
         */
        private Long maxOpenDurationMs = 600000L;
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
    }

    /**
     * HTTP 客户端配置类
     * <p>
     * 配置调用 AI 模型时的底层 HTTP 连接参数。
     * 适用于 Chat、Embedding、Rerank 等所有通过 {@code ModelHttpClient} 发出的请求。
     * </p>
     */
    @Data
    public static class Http {
        /**
         * 连接超时（秒），默认 10
         */
        private Long connectTimeoutSeconds = 10L;

        /**
         * 读取超时（秒），默认 60
         */
        private Long readTimeoutSeconds = 60L;
    }

    /**
     * 同步 Chat 调用配置
     */
    private SyncChat syncChat = new SyncChat();

    /**
     * 同步 Chat 调用配置类
     * <p>
     * 控制 ReACT 迭代等同步 Chat 调用的故障转移行为，防止模型挂起/失败时
     * 在多个候选模型间串联重试（此前单请求最差耗时达 10 分钟）。
     * </p>
     */
    @Data
    public static class SyncChat {
        /**
         * 失败后最多继续尝试的候选模型数（0 = 不限制，逐候选全量尝试）。
         * 默认 1：主模型失败后只再试 1 个候选，单次调用最多 2 次模型请求。
         */
        private Integer maxFallback = 1;
    }
}
