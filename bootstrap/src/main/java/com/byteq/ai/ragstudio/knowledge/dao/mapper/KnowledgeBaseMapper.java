package com.byteq.ai.ragstudio.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;

/**
 * 知识库数据访问接口
 * <p>提供对知识库表（t_knowledge_base）的 CRUD 操作，继承 MyBatis-Plus BaseMapper。</p>
 */
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseDO> {
}
