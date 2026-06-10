package com.byteq.ai.ragstudio.mcp.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.byteq.ai.ragstudio.mcp.dao.entity.McpServerDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * MCP Server 数据访问接口
 * <p>提供对 MCP Server 配置表（t_mcp_server）的 CRUD 操作，继承 MyBatis-Plus BaseMapper。</p>
 */
public interface McpServerMapper extends BaseMapper<McpServerDO> {

    /**
     * 按名称物理删除已逻辑删除的记录
     * <p>
     * 绕过 {@code @TableLogic} 过滤，直接执行 DELETE SQL，
     * 用于清理历史软删除残留，避免唯一约束 {@code uk_mcp_server_name(name, deleted)} 冲突。
     * </p>
     *
     * @param name 服务名称
     * @return 删除行数
     */
    @Delete("DELETE FROM t_mcp_server WHERE name = #{name} AND deleted = 1")
    int physicalDeleteByName(@Param("name") String name);
}
