package com.byteq.ai.ragstudio.knowledge.schedule;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.knowledge.config.KnowledgeScheduleProperties;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定时调度分布式锁管理器
 * <p>
 * 管理文档定时同步任务的分布式锁，确保在多实例部署环境下，
 * 同一个定时任务同时只被一个实例执行。提供锁的获取、续约、释放和心跳保活机制。
 * </p>
 * <p>
 * 锁机制说明：
 * - 基于数据库行级锁实现（通过 UPDATE 条件检查锁到期时间）
 * - 持有锁期间通过定时心跳（Heartbeat）续约，防止任务未完成时锁到期
 * - 锁丢失后任务应中止执行，避免与其他实例产生冲突
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleLockManager {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeScheduleProperties scheduleProperties;

    private final String instancePrefix = resolveInstancePrefix();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            ThreadFactoryBuilder.create()
                    .setNamePrefix("kb_schedule_lock_heartbeat_")
                    .setDaemon(true)
                    .build()
    );

    /**
     * 尝试获取分布式锁
     * <p>通过乐观更新方式获取锁：仅当锁未持有或已过期时才能获取成功。</p>
     *
     * @param scheduleId 调度任务 ID
     * @param now        当前时间
     * @return 如果获取成功返回锁租约，否则返回 null
     */
    public ScheduleLockLease tryAcquire(String scheduleId, Date now) {
        ScheduleLockLease lease = new ScheduleLockLease(scheduleId, nextLockToken());
        int updated = scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, scheduleId)
                        .and(w -> w.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
        );
        return updated > 0 ? lease : null;
    }

    /**
     * 续约分布式锁
     * <p>延长锁的到期时间，必须由锁的持有者才能续约成功。</p>
     *
     * @param lease 锁租约
     * @return 续约成功返回 true，否则返回 false
     */
    public boolean renew(ScheduleLockLease lease) {
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, computeLockUntil())
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    /**
     * 释放分布式锁
     * <p>清除锁持有者和锁到期时间，必须由锁的持有者才能释放成功。</p>
     *
     * @param lease 锁租约
     * @return 释放成功返回 true，否则返回 false
     */
    public boolean release(ScheduleLockLease lease) {
        if (lease == null) {
            return false;
        }
        return scheduleMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getLockOwner, null)
                        .set(KnowledgeDocumentScheduleDO::getLockUntil, null)
                        .eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                        .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken())
        ) > 0;
    }

    /**
     * 启动锁心跳
     * <p>为指定的锁租约启动定时心跳任务，在后台定期续约锁，防止任务执行期间锁到期。</p>
     *
     * @param lease 锁租约
     * @return 心跳控制器
     */
    public ScheduleLockHeartbeat startHeartbeat(ScheduleLockLease lease) {
        long now = System.currentTimeMillis();
        ScheduleLockHeartbeat heartbeat = new ScheduleLockHeartbeat(lease, now, effectiveLockMillis());
        long intervalMillis = computeHeartbeatIntervalMillis();
        ScheduledFuture<?> future = heartbeatExecutor.scheduleWithFixedDelay(
                () -> doHeartbeat(heartbeat),
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        heartbeat.bind(future);
        return heartbeat;
    }

    /**
     * 计算锁到期时间
     *
     * @return 当前时间 + 锁有效时长
     */
    public Date computeLockUntil() {
        return new Date(System.currentTimeMillis() + effectiveLockMillis());
    }

    /**
     * 执行心跳续约
     * <p>尝试续约锁，如果续约失败则标记锁已丢失。</p>
     */
    private void doHeartbeat(ScheduleLockHeartbeat heartbeat) {
        if (heartbeat.isClosed() || heartbeat.isLost()) {
            return;
        }
        try {
            if (renew(heartbeat.lease())) {
                heartbeat.markRenewed();
                return;
            }
            heartbeat.markLost();
            log.warn("定时刷新锁已丢失: scheduleId={}, lockToken={}",
                    heartbeat.lease().scheduleId(), heartbeat.lease().lockToken());
        } catch (Exception e) {
            if (heartbeat.isExpiredWithoutConfirmation()) {
                heartbeat.markLost();
                log.warn("定时刷新锁续约失败且已超过安全窗口: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            } else {
                log.warn("定时刷新锁续约失败，将继续重试: scheduleId={}, lockToken={}",
                        heartbeat.lease().scheduleId(), heartbeat.lease().lockToken(), e);
            }
        }
    }

    // 计算心跳间隔：取锁有效时长的 1/3，限制在 5~60 秒范围内
    private long computeHeartbeatIntervalMillis() {
        long effectiveLockSeconds = effectiveLockSeconds();
        long intervalSeconds = Math.max(5, Math.min(effectiveLockSeconds / 3, 60));
        return intervalSeconds * 1000;
    }

    // 获取锁有效时长（毫秒），最小 60 秒
    private long effectiveLockMillis() {
        return effectiveLockSeconds() * 1000;
    }

    // 获取锁有效时长（秒），取配置值与 60 秒的较大者
    private long effectiveLockSeconds() {
        return Math.max(scheduleProperties.getLockSeconds(), 60L);
    }

    // 生成唯一锁令牌，格式为"实例前缀:UUID"
    private String nextLockToken() {
        return instancePrefix + ":" + UUID.randomUUID();
    }

    // 解析实例前缀，由主机名和随机 UUID 组成，用于标识当前实例
    private static String resolveInstancePrefix() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        return "kb-schedule-" + host + "-" + UUID.randomUUID();
    }

    /**
     * 关闭心跳线程池
     */
    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    /**
     * 锁心跳控制器
     * <p>管理定时任务的锁心跳生命周期，跟踪锁的持有状态、续约时间和丢失状态。
     * 当心跳检测到锁续约失败时，标记锁为丢失状态以通知任务中止执行。</p>
     */
    public static final class ScheduleLockHeartbeat implements AutoCloseable {

        private final ScheduleLockLease lease;
        private final long lockTtlMillis;
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong lastConfirmedAt = new AtomicLong();
        private volatile ScheduledFuture<?> future;

        private ScheduleLockHeartbeat(ScheduleLockLease lease, long startAt, long lockTtlMillis) {
            this.lease = lease;
            this.lockTtlMillis = lockTtlMillis;
            this.lastConfirmedAt.set(startAt);
        }

        private void bind(ScheduledFuture<?> future) {
            this.future = future;
        }

        /**
         * @return 当前心跳管理的锁租约
         */
        public ScheduleLockLease lease() {
            return lease;
        }

        /**
         * @return 锁是否已丢失
         */
        public boolean isLost() {
            return lost.get();
        }

        private boolean isClosed() {
            return closed.get();
        }

        private void markRenewed() {
            lastConfirmedAt.set(System.currentTimeMillis());
        }

        private boolean isExpiredWithoutConfirmation() {
            return System.currentTimeMillis() - lastConfirmedAt.get() >= lockTtlMillis;
        }

        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }

        /**
         * 关闭心跳，取消定时续约任务
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduledFuture = future;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(false);
                }
            }
        }
    }
}
