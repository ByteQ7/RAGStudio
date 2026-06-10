package com.byteq.ai.ragstudio.knowledge.schedule;

/**
 * 调度锁租约记录
 * <p>记录了定时调度任务获取到的分布式锁信息，包含调度任务 ID 和锁令牌。
 * 锁令牌由实例前缀和 UUID 组成，用于唯一标识锁的持有者，
 * 在锁续约和释放操作中验证持有者身份，防止误释放其他实例持有的锁。</p>
 *
 * @param scheduleId 调度任务 ID
 * @param lockToken  锁令牌（由实例标识和 UUID 组成）
 */
public record ScheduleLockLease(String scheduleId, String lockToken) {
}
