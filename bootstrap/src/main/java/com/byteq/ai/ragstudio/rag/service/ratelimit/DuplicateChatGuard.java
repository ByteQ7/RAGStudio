package com.byteq.ai.ragstudio.rag.service.ratelimit;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 新会话重复提交防护
 * <p>
 * 防止同一用户在同一时间窗内重复提交相同问题的新会话请求（多标签页/多设备连点），
 * 导致创建出多个重复会话。同一页面内的双击已由前端 isStreaming 拦截，本防护作为后端兜底。
 * </p>
 * <p>
 * 仅对「新会话」（conversationId 为空）生效：已有会话的重复提交命中会话并发门闸，
 * 此处不做限制，避免误伤合法的连续提问。
 * </p>
 * <p>
 * 实现：Redis 原子操作 {@code SET key 1 NX EX 5s}（{@link RBucket#trySet}），
 * 键存在即视为时间窗内已有相同问题提交。key 使用问题 MD5，避免超长 key 且不落明文。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicateChatGuard {

    /** 重复提交判定时间窗（秒）：窗口内同用户同问题的新会话请求直接拒绝 */
    private static final long WINDOW_SECONDS = 5;

    private static final String KEY_PREFIX = "RAGStudio:chat:dedup:";

    private final RedissonClient redissonClient;

    /**
     * 尝试为新会话请求登记去重标记（不等待）
     *
     * @param userId   用户 ID
     * @param question 用户问题原文
     * @return true-登记成功可执行；false-时间窗内已有相同问题提交
     */
    public boolean tryAcquire(String userId, String question) {
        // 无法确定身份或问题为空时不防护（放行），避免误伤
        if (StrUtil.isBlank(userId) || StrUtil.isBlank(question)) {
            return true;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(userId, question));
            return bucket.trySet("1", WINDOW_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis 不可用时放行（fail-open），避免防护引入新的故障点
            log.warn("重复提交防护登记失败，放行请求: userId={}", userId, e);
            return true;
        }
    }

    private String key(String userId, String question) {
        return KEY_PREFIX + userId + ":" + DigestUtil.md5Hex(question);
    }
}
