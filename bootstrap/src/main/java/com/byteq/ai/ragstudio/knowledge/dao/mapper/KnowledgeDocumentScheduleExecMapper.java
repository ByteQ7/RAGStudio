package com.byteq.ai.ragstudio.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentScheduleExecDO;

/**
 * 知识库文档定时调度执行记录数据访问接口
 * <p>提供对定时调度执行记录表（t_knowledge_document_schedule_exec）的 CRUD 操作，
 * 用于记录每次定时同步任务的执行状态、耗时和结果信息。</p>
 */
public interface KnowledgeDocumentScheduleExecMapper extends BaseMapper<KnowledgeDocumentScheduleExecDO> {
}
