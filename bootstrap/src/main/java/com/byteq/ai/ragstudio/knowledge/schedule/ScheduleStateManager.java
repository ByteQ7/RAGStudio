package com.byteq.ai.ragstudio.knowledge.schedule;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.byteq.ai.ragstudio.knowledge.enums.ScheduleRunStatus;
import com.byteq.ai.ragstudio.knowledge.handler.RemoteFileFetcher;
import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Date;

/**
 * 定时调度状态管理器
 * <p>
 * 负责在定时同步任务执行过程中，安全地更新调度主记录和调度执行记录的状态。
 * 所有写操作都带有锁持有者验证（updateScheduleIfOwned），
 * 确保只有当前持有分布式锁的实例才能修改调度状态，防止并发冲突。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleStateManager {

    private static final String LEASE_LOST_NOTE = "（调度锁已失效，未写回调度状态）";

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper execMapper;

    /**
     * 标记调度执行为跳过（带远程文件变更信息）
     * <p>当远程文件未发生变化时，记录跳过原因并更新 ETag 等文件变更检测信息。</p>
     *
     * @param lease      锁租约
     * @param ctx        调度上下文
     * @param fetchResult 远程文件拉取结果（包含未变更原因和文件元数据）
     * @return 调度主记录更新是否成功（false 表示锁已失效）
     */
    public boolean markSkippedIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult) {
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, fetchResult.message())
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.SKIPPED.getCode());
            execUpdate.setMessage(withLeaseNote(fetchResult.message(), scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execUpdate.setContentHash(fetchResult.contentHash());
            execUpdate.setEtag(fetchResult.etag());
            execUpdate.setLastModified(fetchResult.lastModified());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /**
     * 标记调度执行为跳过（仅带文本消息）
     *
     * @param lease   锁租约
     * @param ctx     调度上下文
     * @param message 跳过原因
     * @return 调度主记录更新是否成功（false 表示锁已失效）
     */
    public boolean markSkippedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String message) {
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SKIPPED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, message)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SKIPPED.getCode())
                    .message(withLeaseNote(message, scheduleUpdated))
                    .endTime(new Date())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /**
     * 标记调度执行为成功
     * <p>更新调度记录的 Cron 表达式、执行时间、下次执行时间、文件变更检测信息等。</p>
     *
     * @param lease       锁租约
     * @param ctx         调度上下文
     * @param fetchResult 远程文件拉取结果
     * @param stored      存储后的文件信息
     * @return 调度主记录更新是否成功（false 表示锁已失效）
     */
    public boolean markSuccessIfOwned(ScheduleLockLease lease,
                                      ScheduleStateContext ctx,
                                      RemoteFileFetcher.RemoteFetchResult fetchResult,
                                      StoredFileDTO stored) {
        Date endTime = new Date();
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastSuccessTime, endTime)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.SUCCESS.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, null)
                        .set(KnowledgeDocumentScheduleDO::getLastEtag, fetchResult.etag())
                        .set(KnowledgeDocumentScheduleDO::getLastModified, fetchResult.lastModified())
                        .set(KnowledgeDocumentScheduleDO::getLastContentHash, fetchResult.contentHash())
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                    .id(ctx.getExecId())
                    .status(ScheduleRunStatus.SUCCESS.getCode())
                    .message(withLeaseNote("刷新成功", scheduleUpdated))
                    .endTime(endTime)
                    .fileName(stored.getOriginalFilename())
                    .fileSize(stored.getSize())
                    .contentHash(fetchResult.contentHash())
                    .etag(fetchResult.etag())
                    .lastModified(fetchResult.lastModified())
                    .build();
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /**
     * 标记调度执行为失败
     *
     * @param lease        锁租约
     * @param ctx          调度上下文
     * @param errorMessage 错误信息（超过 512 字符将被截断）
     * @return 调度主记录更新是否成功（false 表示锁已失效）
     */
    public boolean markFailedIfOwned(ScheduleLockLease lease, ScheduleStateContext ctx, String errorMessage) {
        String truncatedErrorMessage = truncate(errorMessage);
        boolean scheduleUpdated = updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getCronExpr, ctx.getCronExpr())
                        .set(KnowledgeDocumentScheduleDO::getLastRunTime, ctx.getStartTime())
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, ctx.getNextRunTime())
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncatedErrorMessage)
        );

        if (ctx.getExecId() != null) {
            KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
            execUpdate.setId(ctx.getExecId());
            execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
            execUpdate.setMessage(withLeaseNote(truncatedErrorMessage, scheduleUpdated));
            execUpdate.setEndTime(new Date());
            execMapper.updateById(execUpdate);
        }
        return scheduleUpdated;
    }

    /**
     * 禁用调度任务（带锁持有者验证）
     * <p>当文档被删除、禁用或定时表达式不合法时，禁用对应的调度记录。</p>
     *
     * @param lease  锁租约
     * @param reason 禁用原因
     * @return 操作是否成功（false 表示锁已失效）
     */
    public boolean disableIfOwned(ScheduleLockLease lease, String reason) {
        return updateScheduleIfOwned(
                lease,
                Wrappers.lambdaUpdate(KnowledgeDocumentScheduleDO.class)
                        .set(KnowledgeDocumentScheduleDO::getEnabled, 0)
                        .set(KnowledgeDocumentScheduleDO::getNextRunTime, null)
                        .set(KnowledgeDocumentScheduleDO::getLastStatus, ScheduleRunStatus.FAILED.getCode())
                        .set(KnowledgeDocumentScheduleDO::getLastError, truncate(reason))
        );
    }

    /**
     * 标记锁丢失时的执行记录状态
     * <p>当检测到分布式锁已丢失时，更新执行记录状态为 FAILED 并记录原因。</p>
     *
     * @param ctx   调度上下文
     * @param stage 丢失时的执行阶段
     */
    public void markLeaseLost(ScheduleStateContext ctx, String stage) {
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        String message = "调度锁已失效，终止执行";
        if (StringUtils.hasText(stage)) {
            message += ": " + stage;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = new KnowledgeDocumentScheduleExecDO();
        execUpdate.setId(ctx.getExecId());
        execUpdate.setStatus(ScheduleRunStatus.FAILED.getCode());
        execUpdate.setMessage(truncate(message));
        execUpdate.setEndTime(new Date());
        execMapper.updateById(execUpdate);
    }

    /**
     * 仅更新执行记录为成功（不更新调度主记录）
     * <p>用于文档切换已完成但写回调度主记录失败时的降级处理。</p>
     *
     * @param ctx          调度上下文
     * @param stored       存储后的文件信息
     * @param contentHash  内容哈希
     * @param etag         ETag
     * @param lastModified Last-Modified
     * @param message      成功消息
     */
    public void markSuccessExecOnly(ScheduleStateContext ctx,
                                    StoredFileDTO stored,
                                    String contentHash,
                                    String etag,
                                    String lastModified,
                                    String message) {
        if (ctx == null || ctx.getExecId() == null) {
            return;
        }
        KnowledgeDocumentScheduleExecDO execUpdate = KnowledgeDocumentScheduleExecDO.builder()
                .id(ctx.getExecId())
                .status(ScheduleRunStatus.SUCCESS.getCode())
                .message(truncate(message))
                .endTime(new Date())
                .fileName(stored != null ? stored.getOriginalFilename() : null)
                .fileSize(stored != null ? stored.getSize() : null)
                .contentHash(contentHash)
                .etag(etag)
                .lastModified(lastModified)
                .build();
        execMapper.updateById(execUpdate);
    }

    /**
     * 在锁持有者验证下更新调度主记录
     * <p>所有更新操作都通过此方法执行，确保仅当前持有锁的实例可以修改调度状态。</p>
     *
     * @param lease         锁租约
     * @param updateWrapper 更新条件包装器
     * @return 更新是否成功（false 表示锁已失效或记录不存在）
     */
    private boolean updateScheduleIfOwned(ScheduleLockLease lease,
                                          LambdaUpdateWrapper<KnowledgeDocumentScheduleDO> updateWrapper) {
        if (lease == null || updateWrapper == null) {
            return false;
        }
        updateWrapper.eq(KnowledgeDocumentScheduleDO::getId, lease.scheduleId())
                .eq(KnowledgeDocumentScheduleDO::getLockOwner, lease.lockToken());
        return scheduleMapper.update(updateWrapper) > 0;
    }

    /**
     * 附加锁状态说明到消息中
     */
    private String withLeaseNote(String message, boolean scheduleUpdated) {
        if (scheduleUpdated) {
            return truncate(message);
        }
        String baseMessage = StringUtils.hasText(message) ? message.trim() : "执行完成";
        return truncate(baseMessage + LEASE_LOST_NOTE);
    }

    /**
     * 截断消息为最大 512 字符
     */
    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 512) {
            return trimmed;
        }
        return trimmed.substring(0, 512);
    }
}
