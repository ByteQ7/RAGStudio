package com.byteq.ai.ragstudio.mcp.controller.request;

import lombok.Data;

/**
 * 创建 MCP Server 请求参数
 */
@Data
public class McpServerCreateRequest {

    /**
     * 服务名称
     */
    private String name;

    /**
     * 服务地址（MCP Server URL）
     */
    private String url;

    /**
     * 服务描述
     */
    private String description;

    /**
     * 是否启用：1-启用，0-禁用（默认启用）
     */
    private Integer enabled;

    /**
     * 传输类型：streamable_http / sse（默认 streamable_http）
     */
    private String transportType;

    /**
     * 自定义请求头（JSON 字符串，用于认证 Token 等）
     */
    private String headers;
}
