package com.byteq.ai.ragstudio.rag.config;

import com.byteq.ai.ragstudio.rag.service.ratelimit.FairDistributedRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SSE 聊天全局限流器 Bean 装配配置
 * <p>
 * 创建并配置基于 Redis 的分布式公平限流器，用于控制全局并发的 SSE 聊天请求数量。
 * 限流器使用 Redisson 客户端实现分布式协调，确保多实例部署下的限流效果。
 * </p>
 */
@Configuration
public class ChatRateLimiterConfig {

    /** Redis 中限流器的 key 名称 */
    private static final String CHAT_LIMITER_NAME = "RAGStudio:global:chat";

    /**
     * 创建分布式公平限流器 Bean
     * <p>
     * 使用 Redisson 实现的公平信号量，支持排队等待和自动续期。
     * 通过 {@code start} 和 {@code stop} 方法管理限流器的生命周期。
     * </p>
     *
     * @param redissonClient       Redisson 客户端，用于实现分布式协调
     * @param rateLimitProperties  限流配置属性，包含最大并发数等参数
     * @return 分布式公平限流器实例
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public FairDistributedRateLimiter chatRateLimiter(RedissonClient redissonClient,
                                                      RAGRateLimitProperties rateLimitProperties) {
        return new FairDistributedRateLimiter(
                CHAT_LIMITER_NAME,
                redissonClient,
                rateLimitProperties::getGlobalMaxConcurrent,
                rateLimitProperties::getGlobalLeaseSeconds,
                rateLimitProperties::getGlobalPollIntervalMs
        );
    }
}
