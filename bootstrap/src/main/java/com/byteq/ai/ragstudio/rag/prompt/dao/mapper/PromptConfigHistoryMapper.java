package com.byteq.ai.ragstudio.rag.prompt.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.rag.prompt.dao.entity.PromptConfigHistoryDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提示词变更历史数据访问接口
 * <p>提供对 t_prompt_config_history 表的 CRUD 操作。</p>
 */
@Mapper
public interface PromptConfigHistoryMapper extends BaseMapper<PromptConfigHistoryDO> {
}
