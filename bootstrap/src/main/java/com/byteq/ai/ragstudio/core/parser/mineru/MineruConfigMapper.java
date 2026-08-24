package com.byteq.ai.ragstudio.core.parser.mineru;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MinerU 服务端点配置数据访问接口
 * <p>提供对 t_mineru_config 表的 CRUD 操作。</p>
 */
@Mapper
public interface MineruConfigMapper extends BaseMapper<MineruConfigDO> {
}