package com.byteq.ai.ragstudio.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleDO;

/**
 * 知识库文档定时调度数据访问接口
 * <p>提供对文档定时调度表（t_knowledge_document_schedule）的 CRUD 操作，
 * 用于管理文档定时同步任务的调度信息、锁状态和执行记录。</p>
 */
public interface KnowledgeDocumentScheduleMapper extends BaseMapper<KnowledgeDocumentScheduleDO> {
}
