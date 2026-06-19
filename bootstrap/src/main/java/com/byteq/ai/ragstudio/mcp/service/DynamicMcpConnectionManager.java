package com.byteq.ai.ragstudio.mcp.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.service.pipeline.StreamChatPipeline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.mcp.dao.entity.McpServerDO;
import com.byteq.ai.ragstudio.rag.core.mcp.McpClientToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态 MCP 连接管理器
 * <p>
 * 负责在运行时管理外部 MCP Server 的连接生命周期，包括建立连接、断开连接、
 * 重新连接和健康检查。所有操作均为线程安全。
 * </p>
 * <p>
 * 当新增或更新 MCP Server 配置时，调用 {@link #connect(McpServerDO)} 即可立即生效，
 * 远程工具会自动注册到 {@link McpToolRegistry}，供 {@link StreamChatPipeline} 使用。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicMcpConnectionManager {

    private final McpToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    /**
     * 活跃连接池：serverId -> McpSyncClient
     */
    private final Map<String, McpSyncClient> activeClients = new ConcurrentHashMap<>();

    /**
     * 各 Server 注册的工具 ID 列表：serverId -> toolIds
     */
    private final Map<String, List<String>> serverToolIds = new ConcurrentHashMap<>();

    /**
     * 各 Server 的连接状态：serverId -> status
     */
    private final Map<String, String> serverStatuses = new ConcurrentHashMap<>();

    private static final String STATUS_CONNECTED = "connected";
    private static final String STATUS_DISCONNECTED = "disconnected";
    private static final String STATUS_ERROR = "error";

    /**
     * 连接一个 MCP Server 并注册其所有工具
     * <p>
     * 如果该 Server 已有连接，会先断开旧连接再重新建立。
     * 连接成功后自动发现远程工具并注册到 Registry。
     * </p>
     *
     * @param server MCP Server 配置
     * @return 连接结果（成功/失败及工具数量）
     */
    public synchronized ConnectResult connect(McpServerDO server) {
        String serverId = server.getId();
        String serverName = server.getName();

        // 清理旧连接
        disconnectInternal(serverId);

        try {
            String mcpUrl = server.getUrl();
            if (StrUtil.isBlank(mcpUrl)) {
                throw new IllegalArgumentException("MCP Server URL 不能为空");
            }
            Map<String, String> headerMap = parseHeaders(server.getHeaders());
            String transportType = resolveTransportType(server.getTransportType(), mcpUrl);

            McpClientTransport transport = buildTransport(transportType, mcpUrl, headerMap);

            McpSyncClient client = McpClient.sync(transport)
                    .clientInfo(new Implementation("ragstudio-bootstrap", "1.0.0"))
                    .build();
            client.initialize();

            activeClients.put(serverId, client);
            serverStatuses.put(serverId, STATUS_CONNECTED);

            // 发现远程工具
            ListToolsResult result = client.listTools();
            List<Tool> tools = result.tools();
            if (CollUtil.isEmpty(tools)) {
                log.info("MCP Server [{}] 未发现可用工具", serverName);
                return new ConnectResult(true, 0, null);
            }

            // 注册所有工具
            List<String> toolIds = new ArrayList<>();
            for (Tool tool : tools) {
                McpClientToolExecutor executor = new McpClientToolExecutor(client, tool);
                toolRegistry.register(executor);
                toolIds.add(executor.getToolId());
            }
            serverToolIds.put(serverId, toolIds);

            log.info("MCP Server [{}] 连接成功，注册 {} 个工具: {}", serverName, toolIds.size(), toolIds);
            return new ConnectResult(true, toolIds.size(), null);

        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("MCP Server [{}] 连接失败: {}", serverName, reason, e);
            serverStatuses.put(serverId, STATUS_ERROR);
            return new ConnectResult(false, 0, reason);
        }
    }

    /**
     * 断开指定 MCP Server 的连接并注销其所有工具
     *
     * @param serverId MCP Server ID
     */
    public synchronized void disconnect(String serverId) {
        disconnectInternal(serverId);
    }

    /**
     * 刷新连接（配置变更后调用）
     *
     * @param server MCP Server 配置
     * @return 连接结果
     */
    public ConnectResult reconnect(McpServerDO server) {
        return connect(server);
    }

    /**
     * 健康检查：尝试列出远程工具
     *
     * @param serverId MCP Server ID
     * @return 健康状态
     */
    public synchronized HealthStatus healthCheck(String serverId) {
        McpSyncClient client = activeClients.get(serverId);
        if (client == null) {
            return new HealthStatus(STATUS_DISCONNECTED, "未建立连接", 0);
        }
        try {
            ListToolsResult result = client.listTools();
            int toolCount = result.tools() != null ? result.tools().size() : 0;
            serverStatuses.put(serverId, STATUS_CONNECTED);
            return new HealthStatus(STATUS_CONNECTED, null, toolCount);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            serverStatuses.put(serverId, STATUS_ERROR);
            return new HealthStatus(STATUS_ERROR, reason, 0);
        }
    }

    /**
     * 获取指定 Server 的连接状态
     */
    public String getStatus(String serverId) {
        return serverStatuses.getOrDefault(serverId, STATUS_DISCONNECTED);
    }

    /**
     * 获取指定 Server 注册的工具数量
     */
    public int getToolCount(String serverId) {
        List<String> toolIds = serverToolIds.get(serverId);
        return toolIds != null ? toolIds.size() : 0;
    }

    /**
     * 判断是否已有活跃连接
     */
    public boolean isConnected(String serverId) {
        return activeClients.containsKey(serverId);
    }

    @PreDestroy
    public void destroy() {
        log.info("正在关闭所有 MCP 客户端连接...");
        // 先拷贝 key 集合再遍历，避免 disconnectInternal 修改 map 导致跳过元素
        for (String serverId : new ArrayList<>(activeClients.keySet())) {
            disconnectInternal(serverId);
        }
    }

    // ==================== 内部方法 ====================

    // 内部断开逻辑：注销已注册的工具、关闭客户端连接、更新状态为 disconnected
    private void disconnectInternal(String serverId) {
        List<String> toolIds = serverToolIds.remove(serverId);
        if (toolIds != null) {
            for (String toolId : toolIds) {
                toolRegistry.unregister(toolId);
            }
            log.info("已注销 MCP Server [{}] 的 {} 个工具", serverId, toolIds.size());
        }

        McpSyncClient client = activeClients.remove(serverId);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端失败, serverId={}", serverId, e);
            }
        }

        serverStatuses.put(serverId, STATUS_DISCONNECTED);
    }

    // 解析 JSON 格式的请求头字符串为 Map，解析失败返回空 Map
    private Map<String, String> parseHeaders(String headersJson) {
        if (StrUtil.isBlank(headersJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析 MCP Server headers JSON 失败: {}", headersJson, e);
            return Map.of();
        }
    }

    /**
     * 推断传输类型
     * <p>
     * URL 特征优先于配置值：当 URL 以 /sse 结尾时，无论配置如何都使用 SSE 传输。
     * 这是因为 SSE 端点与 Streamable HTTP 端点在协议层面完全不兼容，
     * 用错误的传输类型连接必然失败。
     * </p>
     */
    private String resolveTransportType(String configuredType, String url) {
        // URL 特征优先：以 /sse 结尾的地址只能走 SSE 传输
        if (url.endsWith("/sse")) {
            return "sse";
        }
        if (StrUtil.isNotBlank(configuredType)) {
            return configuredType;
        }
        return "streamable_http";
    }

    /**
     * 根据传输类型构建对应的 MCP Transport
     * <p>
     * 将用户提供的完整 URL 拆分为 baseUri + endpoint，避免 SDK 默认 endpoint 重复拼接。
     * 例如用户输入 {@code https://host/api/sse}，拆分为 baseUri={@code https://host/api} 和 endpoint={@code /sse}。
     * </p>
     * <ul>
     *   <li>SSE：使用 {@link HttpClientSseClientTransport}，默认 endpoint 为 /sse</li>
     *   <li>Streamable HTTP：使用 {@link HttpClientStreamableHttpTransport}，默认 endpoint 为 /mcp</li>
     * </ul>
     */
    private McpClientTransport buildTransport(String transportType, String mcpUrl, Map<String, String> headers) {
        String baseUrl = extractBaseUrl(mcpUrl);
        String path = extractPath(mcpUrl);
        String defaultEndpoint = "sse".equalsIgnoreCase(transportType) ? "/sse" : "/mcp";

        // 如果 URL 路径与 SDK 默认 endpoint 一致，则省略 endpoint 参数（使用 SDK 默认值）
        boolean useDefaultEndpoint = path.isEmpty() || path.equals(defaultEndpoint);

        if ("sse".equalsIgnoreCase(transportType)) {
            log.info("使用 SSE 传输连接: {}, baseUrl={}, endpoint={}", mcpUrl, baseUrl,
                    useDefaultEndpoint ? "(default /sse)" : path);
            HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(baseUrl)
                    .connectTimeout(Duration.ofSeconds(5));
            if (!useDefaultEndpoint) {
                builder.sseEndpoint(path);
            }
            if (!headers.isEmpty()) {
                builder.httpRequestCustomizer((req, method, endpoint, body, context) ->
                        headers.forEach(req::header));
            }
            return builder.build();
        } else {
            log.info("使用 Streamable HTTP 传输连接: {}, baseUrl={}, endpoint={}", mcpUrl, baseUrl,
                    useDefaultEndpoint ? "(default /mcp)" : path);
            HttpClientStreamableHttpTransport.Builder builder = HttpClientStreamableHttpTransport.builder(baseUrl)
                    .connectTimeout(Duration.ofSeconds(5))
                    .resumableStreams(false);
            if (!useDefaultEndpoint) {
                builder.endpoint(path);
            }
            if (!headers.isEmpty()) {
                builder.httpRequestCustomizer((req, method, endpoint, body, context) ->
                        headers.forEach(req::header));
            }
            return builder.build();
        }
    }

    /**
     * 从 URL 中提取基础地址（scheme + host + port，不含路径）
     * <p>例如 {@code https://host:8080/api/sse} → {@code https://host:8080}</p>
     */
    private String extractBaseUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            String authority = uri.getAuthority();
            if (scheme != null && authority != null) {
                return scheme + "://" + authority;
            }
        } catch (Exception e) {
            log.warn("URL 解析失败，使用原始 URL 作为 baseUrl: {}", url);
        }
        return url;
    }

    /**
     * 从 URL 中提取路径部分
     * <p>例如 {@code https://host:8080/api/sse} → {@code /api/sse}；无路径时返回空串</p>
     */
    private String extractPath(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            return (path != null && !path.equals("/")) ? path : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 内部数据类 ====================

    /**
     * 连接结果
     */
    public record ConnectResult(boolean success, int toolCount, String error) {
    }

    /**
     * 健康状态
     */
    public record HealthStatus(String status, String error, int toolCount) {
    }
}
