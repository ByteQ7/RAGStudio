package com.byteq.ai.ragstudio.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeChunkDO;

/**
 * 知识库文档分片数据访问接口
 * <p>提供对知识库文档分片表（t_knowledge_chunk）的 CRUD 操作，继承 MyBatis-Plus BaseMapper。
 * 分片是 RAG 向量检索的最小数据单元。</p>
 */
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {
}
