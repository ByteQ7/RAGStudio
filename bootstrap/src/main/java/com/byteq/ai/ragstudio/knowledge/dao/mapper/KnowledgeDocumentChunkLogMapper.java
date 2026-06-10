package com.byteq.ai.ragstudio.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;

/**
 * 知识库文档分块日志数据访问接口
 * <p>提供对文档分块日志表（t_knowledge_document_chunk_log）的 CRUD 操作，
 * 用于记录和查询文档每次分块处理的详细执行日志和耗时统计。</p>
 */
public interface KnowledgeDocumentChunkLogMapper extends BaseMapper<KnowledgeDocumentChunkLogDO> {
}
