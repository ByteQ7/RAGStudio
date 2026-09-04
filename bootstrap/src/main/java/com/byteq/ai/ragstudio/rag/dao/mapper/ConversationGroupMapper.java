package com.byteq.ai.ragstudio.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationGroupDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话分组 Mapper
 */
@Mapper
public interface ConversationGroupMapper extends BaseMapper<ConversationGroupDO> {
}
