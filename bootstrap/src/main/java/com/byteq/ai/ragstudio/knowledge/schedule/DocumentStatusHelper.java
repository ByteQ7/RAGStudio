package com.byteq.ai.ragstudio.knowledge.schedule;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.knowledge.enums.DocumentStatus;
import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 文档状态辅助工具类
 * <p>
 * 提供文档状态的安全变更操作，用于定时同步任务中管理文档处理状态的流转。
 * 主要功能包括：获取文档处理权（标记为 RUNNING）、标记失败恢复、
 * 更新文件元数据以及恢复长时间卡在 RUNNING 状态的文档。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentStatusHelper {

    private static final String SYSTEM_USER = "system";

    private final KnowledgeDocumentMapper documentMapper;

    /**
     * 尝试将文档标记为处理中状态（RUNNING）
     * <p>仅在文档当前状态不是 RUNNING、未被删除且已启用时才能标记成功。
     * 用于定时同步任务获取文档处理权，防止多实例并发处理同一文档。</p>
     *
     * @param docId 文档 ID
     * @return 标记成功返回 true，否则返回 false（表示文档已被其他任务处理）
     */
    public boolean tryMarkRunning(String docId) {
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
                        .eq(KnowledgeDocumentDO::getEnabled, 1)
                        .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        ) > 0;
    }

    /**
     * 将文档状态从 RUNNING 标记为 FAILED
     * <p>仅在文档当前状态为 RUNNING 时执行，用于定时同步任务失败时的状态回滚。</p>
     *
     * @param docId 文档 ID
     */
    public void markFailedIfRunning(String docId) {
        documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getId, docId)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
        );
    }

    /**
     * 应用定时刷新后的文件元数据
     * <p>定时同步任务从远程拉取新文件后，更新文档的文件名、文件路径、文件类型和大小等元数据。</p>
     *
     * @param docId  文档 ID
     * @param stored 存储后的文件信息
     * @throws ClientException 如果文档不存在
     */
    public void applyRefreshedFileMetadata(String docId, StoredFileDTO stored) {
        KnowledgeDocumentDO update = KnowledgeDocumentDO.builder()
                .id(docId)
                .docName(stored.getOriginalFilename())
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .updatedBy(SYSTEM_USER)
                .build();
        int updated = documentMapper.updateById(update);
        if (updated == 0) {
            throw new ClientException("文档不存在");
        }
    }

    /**
     * 恢复长时间卡在 RUNNING 状态的文档
     * <p>定时任务扫描将超过指定阈值时间仍处于 RUNNING 状态的文档重置为 FAILED，
     * 用于处理因进程崩溃、网络异常等导致的状态卡死问题。</p>
     *
     * @param timeoutMinutes 超时阈值（分钟），最小值为 10
     * @return 恢复成功的文档数量
     */
    public int recoverStuckRunning(long timeoutMinutes) {
        long safeTimeout = Math.max(timeoutMinutes, 10);
        Date threshold = new Date(System.currentTimeMillis() - safeTimeout * 60 * 1000);
        return documentMapper.update(
                Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                        .set(KnowledgeDocumentDO::getStatus, DocumentStatus.FAILED.getCode())
                        .set(KnowledgeDocumentDO::getUpdatedBy, SYSTEM_USER)
                        .eq(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                        .lt(KnowledgeDocumentDO::getUpdateTime, threshold)
        );
    }
}
