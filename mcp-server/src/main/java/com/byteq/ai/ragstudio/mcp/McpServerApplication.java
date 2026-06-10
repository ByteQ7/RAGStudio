package com.byteq.ai.ragstudio.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server 独立启动类
 * <p>
 * MCP（Model Context Protocol）Server 模块的 Spring Boot 启动入口。
 * 负责向 AI 模型提供标准化的工具调用接口（如天气查询、工单查询、销售数据查询），
 * 使大语言模型能够通过 MCP 协议调用外部工具获取实时数据。
 * </p>
 *
 * <p>
 * 该模块作为独立服务运行，同时支持两种传输协议与客户端通信：
 * Streamable HTTP（/mcp）和 HTTP with SSE（/sse）。
 * 注册的工具会自动通过 {@link io.modelcontextprotocol.server.McpSyncServer} 暴露。
 * </p>
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
