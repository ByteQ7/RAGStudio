package com.byteq.ai.ragstudio.rag.service.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 会话级并发门闸
 * <p>
 * 防止同一会话（conversationId）存在多个并发进行中的请求，导致对话历史读写竞态
 * （loadAndAppend 非原子、消息乱序）。
 * </p>
 * <p>
 * 实现：Redis 原子操作 {@code SET key true NX EX}（{@link RBucket#trySet}），
 * 键存在即视为同会话已有请求在处理。键带 TTL 兜底：即使执行路径异常导致显式释放丢失，
 * 标记也会在 TTL 到期后自动消失，不会永久卡死后续请求。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationConcurrencyGate {

    /** 标记 TTL：超过该时长未释放（挂死/异常路径），标记自动过期 */
    private static final long MARK_TTL_MINUTES = 10;

    private static final String KEY_PREFIX = "RAGStudio:chat:conv:";

    private final RedissonClient redissonClient;

    /**
     * 尝试获取会话执行标记（不等待）
     *
     * @param userId         用户 ID（可为 null，使用 anon 兜底）
     * @param conversationId 会话 ID
     * @return true-获取成功可执行；false-同会话已有请求在处理
     */
    public boolean tryAcquire(String userId, String conversationId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(userId, conversationId));
            return bucket.trySet("busy", MARK_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Redis 不可用时放行（fail-open），避免门闸引入新的故障点
            log.warn("会话并发门闸获取失败，放行请求: conversationId={}", conversationId, e);
            return true;
        }
    }

    /**
     * 释放会话执行标记
     * <p>
     * 注意：若持有者执行时间超过 TTL 导致标记已过期且被新请求重新占用，
     * 此处删除可能误删新请求的标记。该场景仅在持有者挂死超过 TTL（10 分钟）
     * 后出现，属于可接受的降级行为。
     * </p>
     *
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     */
    public void release(String userId, String conversationId) {
        try {
            redissonClient.getBucket(key(userId, conversationId)).delete();
        } catch (Exception e) {
            // 释放失败仅记录日志，标记会随 TTL 自动过期
            log.warn("会话并发门闸释放失败: conversationId={}", conversationId, e);
        }
    }

    private String key(String userId, String conversationId) {
        return KEY_PREFIX + (userId == null ? "anon" : userId) + ":" + conversationId;
    }
}
