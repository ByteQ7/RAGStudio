package com.byteq.ai.ragstudio.mcp.controller.request;

import lombok.Data;

/**
 * 更新 MCP Server 请求参数
 */
@Data
public class McpServerUpdateRequest {

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
     * 是否启用：1-启用，0-禁用
     */
    private Integer enabled;

    /**
     * 传输类型：streamable_http / sse
     */
    private String transportType;

    /**
     * 自定义请求头（JSON 字符串）
     */
    private String headers;
}
