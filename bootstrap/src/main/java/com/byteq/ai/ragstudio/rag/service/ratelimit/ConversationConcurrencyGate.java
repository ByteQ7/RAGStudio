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

    /** Redis 故障降级用的本实例内存门闸：单实例部署下 Redis 故障期间同会话仍互斥，防记忆乱序污染 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCAL_FALLBACK_GATE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 尝试获取会话执行标记（不等待）
     *
     * @param userId         用户 ID（可为 null，使用 anon 兜底）
     * @param conversationId 会话 ID
     * @return true-获取成功可执行；false-同会话已有请求在处理
     */
    public boolean tryAcquire(String userId, String conversationId) {
        // 本实例已有降级门闸持有者（Redis 故障期获取）时直接拒绝，
        // 防止"获取时 Redis 故障走内存门闸、恢复后下一请求走 Redis"两条路径互不感知导致同会话并发
        String gateKey = key(userId, conversationId);
        if (LOCAL_FALLBACK_GATE.containsKey(gateKey)) {
            return false;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(userId, conversationId));
            return bucket.trySet("busy", MARK_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            // Redis 不可用时降级为本实例内存门闸（fail-open 仅跨实例，本实例仍互斥），
            // 避免 Redis 故障期间同会话并发请求互相读到不完整历史并乱序落库
            log.warn("会话并发门闸获取失败，降级为本地内存门闸: conversationId={}", conversationId, e);
            return LOCAL_FALLBACK_GATE.putIfAbsent(gateKey, new Object()) == null;
        }
    }

    /**
     * 尝试获取会话执行标记（限时等待）
     * <p>用于低频辅助写路径（如限流拒绝记录）：拿不到说明同会话真实请求正在执行中，
     * 调用方应跳过本次写入避免与真实回答交错。</p>
     *
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     * @param waitSeconds    最长等待秒数
     * @return true-获取成功；false-等待期内门闸仍被占用
     */
    public boolean tryAcquireWait(String userId, String conversationId, long waitSeconds) {
        String gateKey = key(userId, conversationId);
        // 与 tryAcquire 一致：本地降级门闸持有者优先
        if (LOCAL_FALLBACK_GATE.containsKey(gateKey)) {
            return false;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(userId, conversationId));
            // 先检查是否已被占用，避免无谓等待
            if (bucket.isExists()) {
                return false;
            }
            long deadline = System.currentTimeMillis() + Math.max(0, waitSeconds) * 1000;
            do {
                if (bucket.trySet("busy", MARK_TTL_MINUTES, TimeUnit.MINUTES)) {
                    return true;
                }
                if (System.currentTimeMillis() >= deadline) {
                    return false;
                }
                Thread.sleep(100);
            } while (true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("会话并发门闸限时获取失败，降级为本地内存门闸: conversationId={}", conversationId, e);
            return LOCAL_FALLBACK_GATE.putIfAbsent(gateKey, new Object()) == null;
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
        // 清理可能存在的本地降级门闸条目（无论释放路径走 Redis 还是内存，幂等安全）
        LOCAL_FALLBACK_GATE.remove(key(userId, conversationId));
    }

    private String key(String userId, String conversationId) {
        return KEY_PREFIX + (userId == null ? "anon" : userId) + ":" + conversationId;
    }
}
