package com.byteq.ai.ragstudio.aimodel.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiModelDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * AI 模型配置数据访问接口
 */
public interface AiModelMapper extends BaseMapper<AiModelDO> {

    /**
     * 物理删除指定 model_id 且已软删除的记录
     * <p>
     * 绕过 @TableLogic，执行真正的 DELETE 操作。
     * 用于在逻辑删除前清理之前软删除的重复记录，
     * 避免违反 uk_ai_model_model_id 唯一约束。
     * </p>
     */
    @Delete("DELETE FROM t_ai_model WHERE model_id = #{modelId} AND deleted = 1")
    int forceDeleteByModelId(@Param("modelId") String modelId);
}
