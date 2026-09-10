package com.byteq.ai.ragstudio.knowledge.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.ingestion.dao.entity.IngestionTaskDO;
import com.byteq.ai.ragstudio.ingestion.dao.mapper.IngestionTaskMapper;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionStatus;
import com.byteq.ai.ragstudio.knowledge.config.KnowledgeScheduleProperties;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识库文档定时同步任务调度器
 * <p>
 * 基于 Spring 的 @Scheduled 注解驱动，周期性扫描文档定时调度表，
 * 发现到期的定时任务后尝试获取分布式锁，提交到线程池异步执行同步流程。
 * 同时提供文档状态恢复机制，处理因异常导致的文档状态卡死问题。
 * </p>
 * <p>
 * 调度设计：
 * - 定期扫描（默认 10 秒一次）已启用且到达执行时间的调度记录
 * - 通过分布式锁确保每个任务同时只被一个实例执行
 * - 异步执行，避免扫描周期被阻塞
 * - 独立的状态恢复机制（60 秒一次），处理因进程崩溃导致的 RUNNING 状态卡死
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeDocumentScheduleJob {

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    @Qualifier("knowledgeChunkExecutor")
    private final Executor knowledgeChunkExecutor;
    private final KnowledgeScheduleProperties scheduleProperties;
    private final ScheduleLockManager lockManager;
    private final ScheduleRefreshProcessor scheduleRefreshProcessor;
    private final DocumentStatusHelper documentStatusHelper;
    private final IngestionTaskMapper ingestionTaskMapper;

    /**
     * 恢复长时间卡在 RUNNING 状态的文档
     * <p>处理因进程崩溃、服务器宕机等异常场景导致的文档处理状态卡死。
     * 超过配置阈值（默认 30 分钟）仍处于 RUNNING 状态的文档会被重置为 FAILED，
     * 允许用户手动重试。</p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void recoverStuckRunningDocuments() {
        long timeoutMinutes = scheduleProperties.getRunningTimeoutMinutes();
        int recovered = documentStatusHelper.recoverStuckRunning(timeoutMinutes);
        if (recovered > 0) {
            log.warn("恢复了 {} 个卡在 RUNNING 状态超过 {} 分钟的文档，已重置为 FAILED",
                    recovered, Math.max(timeoutMinutes, 10));
        }
    }

    /**
     * 恢复长时间卡在 RUNNING 状态的流水线摄入任务（t_ingestion_task）
     * <p>t_knowledge_document 有 recoverStuckRunning 兜底，但摄入任务表此前无任何恢复机制，
     * 进程在中途崩溃后任务会永远停留在 RUNNING。与文档恢复共用同一超时阈值，
     * 更新带 RUNNING 条件保护，多实例并发执行幂等无害。</p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void recoverStuckRunningIngestionTasks() {
        // 摄入任务执行期间不刷新 updateTime（仅开始/结束时落库），阈值放宽为文档阈值的 2 倍，
        // 避免误杀合法的长流水线任务
        long timeoutMinutes = Math.max(scheduleProperties.getRunningTimeoutMinutes() * 2, 30);
        Date threshold = new Date(System.currentTimeMillis() - timeoutMinutes * 60 * 1000);
        int recovered = ingestionTaskMapper.update(null,
                Wrappers.lambdaUpdate(IngestionTaskDO.class)
                        .set(IngestionTaskDO::getStatus, IngestionStatus.FAILED.getValue())
                        .set(IngestionTaskDO::getErrorMessage, "任务超时未完成（疑似进程中断），由定时任务自动恢复")
                        .set(IngestionTaskDO::getCompletedAt, new Date())
                        .set(IngestionTaskDO::getUpdatedBy, "system")
                        .eq(IngestionTaskDO::getStatus, IngestionStatus.RUNNING.getValue())
                        .lt(IngestionTaskDO::getUpdateTime, threshold)
        );
        if (recovered > 0) {
            log.warn("恢复了 {} 个卡在 RUNNING 状态超过 {} 分钟的摄入任务，已重置为 FAILED",
                    recovered, timeoutMinutes);
        }
    }

    /**
     * 扫描并触发到期的定时同步任务
     * <p>查询所有已启用、到达执行时间且未锁定的调度记录，
     * 逐个尝试获取分布式锁，成功则将任务提交到异步线程池执行。</p>
     */
    @Scheduled(fixedDelayString = "${rag.knowledge.schedule.scan-delay-ms:10000}")
    public void scan() {
        Date now = new Date();
        List<KnowledgeDocumentScheduleDO> schedules = scheduleMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocumentScheduleDO>()
                        .eq(KnowledgeDocumentScheduleDO::getEnabled, 1)
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getNextRunTime)
                                .or()
                                .le(KnowledgeDocumentScheduleDO::getNextRunTime, now))
                        .and(wrapper -> wrapper.isNull(KnowledgeDocumentScheduleDO::getLockUntil)
                                .or()
                                .lt(KnowledgeDocumentScheduleDO::getLockUntil, now))
                        .orderByAsc(KnowledgeDocumentScheduleDO::getNextRunTime)
                        .last("LIMIT " + Math.max(scheduleProperties.getBatchSize(), 1))
        );

        if (schedules == null || schedules.isEmpty()) {
            return;
        }

        for (KnowledgeDocumentScheduleDO schedule : schedules) {
            if (schedule == null || schedule.getId() == null) {
                continue;
            }
            ScheduleLockLease lease = lockManager.tryAcquire(schedule.getId(), now);
            if (lease == null) {
                continue;
            }
            try {
                knowledgeChunkExecutor.execute(() -> scheduleRefreshProcessor.process(lease));
            } catch (RejectedExecutionException e) {
                log.error("定时任务提交失败: scheduleId={}, docId={}, kbId={}",
                        schedule.getId(), schedule.getDocId(), schedule.getKbId(), e);
                lockManager.release(lease);
            }
        }
    }
}
