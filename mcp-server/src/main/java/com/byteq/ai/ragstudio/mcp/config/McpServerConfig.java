package com.byteq.ai.ragstudio.mcp.config;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 核心配置类
 * <p>
 * 配置 MCP（Model Context Protocol）服务端的传输层和 Server 实例。
 * 同时支持两种传输协议：
 * </p>
 * <ul>
 *   <li><b>Streamable HTTP</b>（2025-03-26 规范）：注册在 {@code /mcp} 路径，适用于新版 MCP 客户端</li>
 *   <li><b>HTTP with SSE</b>（2024-11-05 规范）：注册在 {@code /sse} 路径，兼容旧版 MCP 客户端</li>
 * </ul>
 *
 * <p>
 * 两种传输各自创建独立的 {@link McpSyncServer} 实例，共享同一套工具定义。
 * </p>
 */
@Configuration
public class McpServerConfig {

    // ==================== Streamable HTTP 传输 ====================

    /**
     * 创建 HTTP Servlet 流式传输提供者（Streamable HTTP，2025-03-26 规范）
     */
    @Bean
    public HttpServletStreamableServerTransportProvider streamableTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .build();
    }

    /**
     * 注册 Streamable HTTP MCP Servlet，监听 /mcp 路径
     */
    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> streamableMcpServlet(
            HttpServletStreamableServerTransportProvider streamableTransportProvider) {
        return new ServletRegistrationBean<>(streamableTransportProvider, "/mcp");
    }

    /**
     * 创建 Streamable HTTP 传输的 MCP 同步服务端
     */
    @Bean
    public McpSyncServer streamableMcpServer(HttpServletStreamableServerTransportProvider streamableTransportProvider,
                                              List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        return McpServer.sync(streamableTransportProvider)
                .serverInfo("ragstudio-mcp-server", "0.0.1")
                .tools(toolSpecs)
                .build();
    }

    // ==================== SSE 传输 ====================

    /**
     * 创建 HTTP Servlet SSE 传输提供者（HTTP with SSE，2024-11-05 规范）
     * <p>
     * SSE 传输需要两个端点：
     * <ul>
     *   <li>{@code /sse}：客户端建立 SSE 长连接接收服务端推送</li>
     *   <li>{@code /message}：客户端发送 JSON-RPC 消息</li>
     * </ul>
     */
    @Bean
    public HttpServletSseServerTransportProvider sseTransportProvider() {
        return HttpServletSseServerTransportProvider.builder()
                .messageEndpoint("/message")
                .sseEndpoint("/sse")
                .build();
    }

    /**
     * 注册 SSE MCP Servlet，同时监听 /sse（SSE 连接）和 /message（消息投递）路径
     */
    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> sseMcpServlet(
            HttpServletSseServerTransportProvider sseTransportProvider) {
        return new ServletRegistrationBean<>(sseTransportProvider, "/sse", "/message");
    }

    /**
     * 创建 SSE 传输的 MCP 同步服务端
     */
    @Bean
    public McpSyncServer sseMcpServer(HttpServletSseServerTransportProvider sseTransportProvider,
                                       List<McpServerFeatures.SyncToolSpecification> toolSpecs) {
        return McpServer.sync(sseTransportProvider)
                .serverInfo("ragstudio-mcp-server", "0.0.1")
                .tools(toolSpecs)
                .build();
    }
}
