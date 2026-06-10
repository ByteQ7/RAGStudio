package com.byteq.ai.ragstudio.knowledge.service;

import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;

/**
 * 知识库文档定时同步任务服务接口
 * <p>
 * 定义文档定时同步任务的创建、更新、同步启用/禁用状态以及删除操作。
 * 该服务用于管理基于 Cron 表达式的定时文档刷新同步功能，适用于从远程 URL 定期拉取文档更新的场景。
 * 定时同步的核心流程：定时触发 → 检查远程文件是否变更（ETag/Last-Modified/内容哈希） →
 * 如有变更则重新下载 → 重新执行文档分块和向量化 → 更新文档元数据。
 */
public interface KnowledgeDocumentScheduleService {

    /**
     * 根据文档信息创建或更新定时任务记录
     * <p>当文档设置了定时同步（scheduleEnabled=1）时，创建或更新对应的定时任务调度记录，
     * 并根据 Cron 表达式计算下次执行时间。</p>
     *
     * @param documentDO 文档实体（需包含 scheduleCron、scheduleEnabled 等字段）
     */
    void upsertSchedule(KnowledgeDocumentDO documentDO);

    /**
     * 当文档启用/禁用时同步定时任务（仅更新已存在的任务）
     * <p>当文档的启用状态变更时，同步更新定时调度记录的启用状态，
     * 仅对已存在调度记录的文档生效，不会创建新的调度记录。</p>
     *
     * @param documentDO 文档实体
     */
    void syncScheduleIfExists(KnowledgeDocumentDO documentDO);

    /**
     * 删除文档关联的定时任务及执行记录
     * <p>删除指定文档关联的所有定时调度记录和历史执行记录。</p>
     *
     * @param docId 文档 ID
     */
    void deleteByDocId(String docId);
}
