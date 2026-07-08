package com.byteq.ai.ragstudio.knowledge.schedule;

import com.byteq.ai.ragstudio.framework.context.LoginUser;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentScheduleExecMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentScheduleMapper;
import com.byteq.ai.ragstudio.knowledge.enums.DocumentStatus;
import com.byteq.ai.ragstudio.knowledge.enums.ScheduleRunStatus;
import com.byteq.ai.ragstudio.knowledge.enums.SourceType;
import com.byteq.ai.ragstudio.knowledge.handler.RemoteFileFetcher;
import com.byteq.ai.ragstudio.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import com.byteq.ai.ragstudio.rag.constant.RAGConstant;
import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.Date;

/**
 * 定时调度刷新处理器
 * <p>
 * 负责执行文档定时同步的核心业务流程。当调度任务被触发时，从远程 URL 拉取文件，
 * 检测文件是否变更（通过 ETag/Last-Modified/内容哈希），如有变更则重新执行
 * 下载、分块、向量化并更新文档元数据的完整流程。
 * </p>
 * <p>
 * 核心执行流程：
 * 1. 验证锁有效性和文档可用性
 * 2. 启动锁心跳保活
 * 3. 检查定时表达式和来源类型有效性
 * 4. 创建执行记录
 * 5. 从远程获取文件并检测变更
 * 6. 尝试获取文档处理权（标记 RUNNING）
 * 7. 下载文件并存储
 * 8. 执行文档分块和向量化
 * 9. 更新文件元数据
 * 10. 写回调度执行结果
 * 11. 释放锁并清理临时文件
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleRefreshProcessor {

    private static final String SYSTEM_USER = "system";

    private final KnowledgeDocumentScheduleMapper scheduleMapper;
    private final KnowledgeDocumentScheduleExecMapper execMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper kbMapper;
    private final KnowledgeDocumentServiceImpl documentService;
    private final FileStorageService fileStorageService;
    private final RemoteFileFetcher remoteFileFetcher;

    private final ScheduleLockManager lockManager;
    private final ScheduleStateManager stateManager;
    private final DocumentStatusHelper documentStatusHelper;

    /**
     * 执行定时刷新处理
     * <p>完整的定时同步执行入口，包含锁心跳管理、文件拉取、分块处理、状态写回等全部环节。</p>
     *
     * @param lease 调度锁租约
     */
    public void process(ScheduleLockLease lease) {
        if (lease == null) {
            return;
        }
        String scheduleId = lease.scheduleId();
        Date startTime = new Date();
        if (shouldAbortForLeaseLoss(lease, null, "任务启动")) {
            log.info("定时刷新任务启动时已失去锁，跳过执行: scheduleId={}, lockToken={}",
                    lease.scheduleId(), lease.lockToken());
            return;
        }

        ScheduleLockManager.ScheduleLockHeartbeat heartbeat;
        try {
            heartbeat = lockManager.startHeartbeat(lease);
        } catch (Exception e) {
            log.error("定时刷新启动锁心跳失败，释放锁并终止执行: scheduleId={}, lockToken={}",
                    lease.scheduleId(), lease.lockToken(), e);
            boolean released = lockManager.release(lease);
            if (!released) {
                log.warn("定时刷新启动锁心跳失败且释放锁失败: scheduleId={}, lockToken={}",
                        lease.scheduleId(), lease.lockToken());
            }
            return;
        }
        RefreshRunState state = new RefreshRunState();
        try {
            KnowledgeDocumentScheduleDO schedule = scheduleMapper.selectById(scheduleId);
            if (schedule == null) {
                return;
            }

            state.document = documentMapper.selectById(schedule.getDocId());
            if (state.document == null || (state.document.getDeleted() != null && state.document.getDeleted() == 1)) {
                disableIfOwnedOrMarkLeaseLost(lease, state, "文档不存在或已删除", "禁用调度: 文档不存在或已删除");
                return;
            }
            if (state.document.getEnabled() != null && state.document.getEnabled() == 0) {
                disableIfOwnedOrMarkLeaseLost(lease, state, "文档已禁用", "禁用调度: 文档已禁用");
                return;
            }

            String cron = state.document.getScheduleCron();
            boolean enabled = state.document.getScheduleEnabled() != null && state.document.getScheduleEnabled() == 1;
            if (!StringUtils.hasText(cron) || !SourceType.URL.getValue().equalsIgnoreCase(state.document.getSourceType())) {
                enabled = false;
            }

            schedule.setCronExpr(cron);
            Date nextRunTime;
            if (enabled) {
                try {
                    nextRunTime = CronScheduleHelper.nextRunTime(cron, startTime);
                } catch (IllegalArgumentException e) {
                    disableIfOwnedOrMarkLeaseLost(lease, state, "定时表达式不合法", "禁用调度: 定时表达式不合法");
                    return;
                }
                if (nextRunTime == null) {
                    disableIfOwnedOrMarkLeaseLost(lease, state, "无法计算下次执行时间", "禁用调度: 无法计算下次执行时间");
                    return;
                }
            } else {
                disableIfOwnedOrMarkLeaseLost(lease, state, "定时已关闭", "禁用调度: 定时已关闭");
                return;
            }

            KnowledgeDocumentScheduleExecDO exec = KnowledgeDocumentScheduleExecDO.builder()
                    .scheduleId(scheduleId)
                    .docId(state.document.getId())
                    .kbId(state.document.getKbId())
                    .status(ScheduleRunStatus.RUNNING.getCode())
                    .startTime(startTime)
                    .build();
            execMapper.insert(exec);

            state.ctx = ScheduleStateContext.builder()
                    .scheduleId(schedule.getId())
                    .execId(exec.getId())
                    .cronExpr(schedule.getCronExpr())
                    .startTime(startTime)
                    .nextRunTime(nextRunTime)
                    .build();

            try (RemoteFileFetcher.RemoteFetchResult fetchResult = remoteFileFetcher.fetchIfChanged(
                    state.document.getSourceLocation(),
                    schedule.getLastEtag(),
                    schedule.getLastModified(),
                    schedule.getLastContentHash(),
                    state.document.getDocName()
            )) {
                state.fetch = FetchSnapshot.from(fetchResult);

                if (!fetchResult.changed()) {
                    markSkippedIfOwnedOrMarkLeaseLost(lease, state, fetchResult, "远程文件未变化");
                    return;
                }

                if (DocumentStatus.RUNNING.getCode().equals(state.document.getStatus())) {
                    markSkippedIfOwnedOrMarkLeaseLost(lease, state, "文档正在分块中，跳过本次调度", "文档占用中，跳过调度");
                    return;
                }

                if (shouldAbortForLeaseLoss(lease, heartbeat, "领取文档运行权")) {
                    state.leaseLost = true;
                    stateManager.markLeaseLost(state.ctx, "领取文档运行权");
                    return;
                }
                if (!documentStatusHelper.tryMarkRunning(state.document.getId())) {
                    markSkippedIfOwnedOrMarkLeaseLost(lease, state, "文档正在分块中，跳过本次调度", "文档运行权争抢失败");
                    return;
                }
                state.phase = Phase.DOC_OCCUPIED;

                KnowledgeBaseDO kbDO = kbMapper.selectById(state.document.getKbId());
                if (kbDO == null) {
                    throw new ClientException("知识库不存在");
                }

                state.oldFileUrl = state.document.getFileUrl();
                try (InputStream tempIn = Files.newInputStream(fetchResult.tempFile())) {
                    String documentPrefix = RAGConstant.S3_DOCUMENT_PREFIX + "/" + kbDO.getCollectionName();
                    state.stored = fileStorageService.upload(
                            RAGConstant.S3_BUCKET_NAME,
                            documentPrefix,
                            tempIn,
                            fetchResult.size(),
                            fetchResult.fileName(),
                            fetchResult.contentType()
                    );
                }

                KnowledgeDocumentDO runtimeDoc = documentMapper.selectById(state.document.getId());
                if (runtimeDoc == null) {
                    throw new ClientException("文档不存在");
                }
                runtimeDoc.setDocName(state.stored.getOriginalFilename());
                runtimeDoc.setFileUrl(state.stored.getUrl());
                runtimeDoc.setFileType(state.stored.getDetectedType());
                runtimeDoc.setFileSize(state.stored.getSize());
                runtimeDoc.setUpdatedBy(SYSTEM_USER);

                if (shouldAbortForLeaseLoss(lease, heartbeat, "执行文档分块")) {
                    state.leaseLost = true;
                    stateManager.markLeaseLost(state.ctx, "执行文档分块");
                    return;
                }
                state.phase = Phase.CHUNK_STARTED;
                UserContext.set(LoginUser.builder().username(SYSTEM_USER).build());
                try {
                    documentService.chunkDocument(runtimeDoc);
                } finally {
                    UserContext.clear();
                }

                KnowledgeDocumentDO latest = documentMapper.selectById(state.document.getId());
                if (latest == null || !DocumentStatus.SUCCESS.getCode().equals(latest.getStatus())) {
                    markFailedIfOwnedOrMarkLeaseLost(lease, state, "分块失败", "分块失败写回调度状态");
                    return;
                }

                state.phase = Phase.CHUNK_COMPLETED;
                documentStatusHelper.applyRefreshedFileMetadata(state.document.getId(), state.stored);
                state.phase = Phase.FILE_SWITCHED;

                markSuccessIfOwnedOrMarkLeaseLost(lease, state, fetchResult, "刷新成功写回调度状态");
            }
        } catch (Exception e) {
            log.error("定时刷新失败: scheduleId={}, docId={}, kbId={}",
                    scheduleId,
                    state.document != null ? state.document.getId() : null,
                    state.document != null ? state.document.getKbId() : null,
                    e);
            if (state.phase != Phase.FILE_SWITCHED) {
                if (state.hasDocumentOccupied()) {
                    documentStatusHelper.markFailedIfRunning(state.document.getId());
                }
                if (state.ctx != null) {
                    markFailedIfOwnedOrMarkLeaseLost(lease, state, e.getMessage(), "异常失败写回调度状态");
                }
            } else if (state.ctx != null) {
                stateManager.markSuccessExecOnly(
                        state.ctx,
                        state.stored,
                        state.fetch != null ? state.fetch.contentHash() : null,
                        state.fetch != null ? state.fetch.etag() : null,
                        state.fetch != null ? state.fetch.lastModified() : null,
                        "刷新成功（调度状态写回失败）"
                );
                log.error("定时刷新已完成文档切换，但写回调度状态失败: scheduleId={}, lockToken={}",
                        lease.scheduleId(), lease.lockToken(), e);
            }
        } finally {
            heartbeat.close();
            if (state.leaseLost && state.phase == Phase.DOC_OCCUPIED && state.document != null) {
                documentStatusHelper.markFailedIfRunning(state.document.getId());
            }
            if (state.phase == Phase.FILE_SWITCHED) {
                deleteOldFileQuietly(state.oldFileUrl, state.stored != null ? state.stored.getUrl() : null);
            } else if (state.stored != null && state.phase.ordinal() < Phase.CHUNK_COMPLETED.ordinal()) {
                deleteOldFileQuietly(state.stored.getUrl(), null);
            } else if (state.stored != null) {
                log.warn("定时刷新分块已完成但未完成文件元数据切换，保留新文件待后续处理: scheduleId={}, docId={}, fileUrl={}",
                        scheduleId, state.document != null ? state.document.getId() : null, state.stored.getUrl());
            }
            boolean released = lockManager.release(lease);
            if (!released && !state.leaseLost && !heartbeat.isLost()) {
                log.warn("定时刷新释放锁失败: scheduleId={}, lockToken={}",
                        lease.scheduleId(), lease.lockToken());
            }
        }
    }

    /**
     * 禁用调度（带锁持有者验证），锁失效时标记丢失
     */
    private void disableIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                               RefreshRunState state,
                                               String reason,
                                               String logStage) {
        if (!stateManager.disableIfOwned(lease, reason)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /**
     * 标记跳过（带远程文件变更信息，带锁持有者验证），锁失效时标记丢失
     */
    private void markSkippedIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                   RefreshRunState state,
                                                   RemoteFileFetcher.RemoteFetchResult fetchResult,
                                                   String logStage) {
        if (!stateManager.markSkippedIfOwned(lease, state.ctx, fetchResult)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /**
     * 标记跳过（仅文本消息，带锁持有者验证），锁失效时标记丢失
     */
    private void markSkippedIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                   RefreshRunState state,
                                                   String message,
                                                   String logStage) {
        if (!stateManager.markSkippedIfOwned(lease, state.ctx, message)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /**
     * 标记失败（带锁持有者验证），锁失效时标记丢失
     */
    private void markFailedIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                  RefreshRunState state,
                                                  String message,
                                                  String logStage) {
        if (!stateManager.markFailedIfOwned(lease, state.ctx, message)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /**
     * 标记成功（带锁持有者验证），锁失效时标记丢失
     */
    private void markSuccessIfOwnedOrMarkLeaseLost(ScheduleLockLease lease,
                                                   RefreshRunState state,
                                                   RemoteFileFetcher.RemoteFetchResult fetchResult,
                                                   String logStage) {
        if (!stateManager.markSuccessIfOwned(lease, state.ctx, fetchResult, state.stored)) {
            state.leaseLost = true;
            logScheduleStateWriteSkipped(lease, logStage);
        }
    }

    /**
     * 检查锁是否已丢失，并根据需要中止执行
     *
     * @return true-需要中止执行，false-可以继续
     */
    private boolean shouldAbortForLeaseLoss(ScheduleLockLease lease,
                                            ScheduleLockManager.ScheduleLockHeartbeat heartbeat,
                                            String stage) {
        if (heartbeat != null && heartbeat.isLost()) {
            log.warn("定时刷新锁已丢失，停止继续执行: scheduleId={}, stage={}, lockToken={}",
                    lease.scheduleId(), stage, lease.lockToken());
            return true;
        }
        try {
            boolean renewed = lockManager.renew(lease);
            if (!renewed) {
                log.warn("定时刷新锁续约失败，停止继续执行: scheduleId={}, stage={}, lockToken={}",
                        lease.scheduleId(), stage, lease.lockToken());
            }
            return !renewed;
        } catch (Exception e) {
            log.warn("定时刷新锁续约异常，停止继续执行: scheduleId={}, stage={}, lockToken={}",
                    lease.scheduleId(), stage, lease.lockToken(), e);
            return true;
        }
    }

    /**
     * 记录调度状态写回被跳过的日志
     */
    private void logScheduleStateWriteSkipped(ScheduleLockLease lease, String stage) {
        log.warn("定时刷新锁已失效，未写回调度主状态: scheduleId={}, stage={}, lockToken={}",
                lease.scheduleId(), stage, lease.lockToken());
    }

    /**
     * 静默删除旧文件
     */
    private void deleteOldFileQuietly(String oldFileUrl, String newFileUrl) {
        if (!StringUtils.hasText(oldFileUrl) || oldFileUrl.equals(newFileUrl)) {
            return;
        }
        try {
            fileStorageService.deleteByUrl(oldFileUrl);
        } catch (Exception e) {
            log.warn("定时刷新文件清理失败: {}", oldFileUrl, e);
        }
    }

    /**
     * 执行阶段枚举，用于跟踪定时刷新任务的处理进度
     */
    private enum Phase {
        INIT,
        DOC_OCCUPIED,
        CHUNK_STARTED,
        CHUNK_COMPLETED,
        FILE_SWITCHED
    }

    /**
     * 刷新执行状态的内部类，跟踪一次调度刷新过程中的所有中间状态
     */
    private static final class RefreshRunState {

        private KnowledgeDocumentDO document;
        private ScheduleStateContext ctx;
        private String oldFileUrl;
        private StoredFileDTO stored;
        private boolean leaseLost;
        private Phase phase = Phase.INIT;
        private FetchSnapshot fetch;

        private boolean hasDocumentOccupied() {
            return phase.ordinal() >= Phase.DOC_OCCUPIED.ordinal();
        }
    }

    /**
     * 远程文件拉取快照，记录文件的变更检测信息
     */
    private record FetchSnapshot(String contentHash, String etag, String lastModified) {

        private static FetchSnapshot from(RemoteFileFetcher.RemoteFetchResult fetchResult) {
            if (fetchResult == null) {
                return null;
            }
            return new FetchSnapshot(fetchResult.contentHash(), fetchResult.etag(), fetchResult.lastModified());
        }
    }
}
