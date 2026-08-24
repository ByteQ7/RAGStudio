package com.byteq.ai.ragstudio.graph.config;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Graph RAG 运行期配置数据访问接口
 * <p>提供对 t_graph_config 表的 CRUD 操作。</p>
 */
@Mapper
public interface GraphConfigMapper extends BaseMapper<GraphConfigDO> {
}