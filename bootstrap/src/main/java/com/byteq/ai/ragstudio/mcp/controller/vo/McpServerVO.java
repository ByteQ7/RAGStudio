package com.byteq.ai.ragstudio.mcp.controller.vo;

import lombok.Data;

import java.util.Date;

/**
 * MCP Server 前端返回对象
 */
@Data
public class McpServerVO {

    /**
     * MCP Server ID
     */
    private String id;

    /**
     * 服务名称
     */
    private String name;

    /**
     * 服务地址
     */
    private String url;

    /**
     * 服务描述
     */
    private String description;

    /**
     * 是否启用
     */
    private Integer enabled;

    /**
     * 传输类型
     */
    private String transportType;

    /**
     * 自定义请求头（JSON）
     */
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
     * 已注册的工具数量（运行时数据，不持久化）
     */
    private Integer toolCount;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
