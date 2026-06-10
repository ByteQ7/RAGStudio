package com.byteq.ai.ragstudio.mcp.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.byteq.ai.ragstudio.knowledge.dao.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * MCP Server 动态配置实体
 * <p>映射数据库表 t_mcp_server，用于动态管理外部 MCP Server 的连接配置。
 * 支持运行时增删改查 MCP Server，无需重启应用。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName(value = "t_mcp_server", autoResultMap = true)
public class McpServerDO {

    /**
     * 主键 ID（雪花算法自动生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 服务名称（唯一标识）
     */
    private String name;

    /**
     * 服务地址（MCP Server 的 URL）
     */
    private String url;

    /**
     * 服务描述
     */
    private String description;

    /**
     * 是否启用：1-启用，0-禁用
     */
    private Integer enabled;

    /**
     * 传输类型：streamable_http / sse
     */
    private String transportType;

    /**
     * 自定义请求头（JSON 格式，用于认证 Token 等）
     * 使用 JsonbTypeHandler 处理 PostgreSQL jsonb 类型
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String headers;

    /**
     * 最近连接状态：connected / disconnected / error
     */
    private String lastStatus;

    /**
     * 最近错误信息
     */
    private String lastError;

    /**
     * 最近检查时间
     */
    private Date lastCheckTime;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    /**
     * 创建时间（由 MyBatis-Plus 自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间（由 MyBatis-Plus 自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 是否删除：0-正常，1-删除（逻辑删除标识）
     */
    @TableLogic
    private Integer deleted;
}
