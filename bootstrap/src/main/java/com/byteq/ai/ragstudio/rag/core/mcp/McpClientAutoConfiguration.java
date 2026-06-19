package com.byteq.ai.ragstudio.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.mcp.dao.entity.McpServerDO;
import com.byteq.ai.ragstudio.mcp.service.DynamicMcpConnectionManager;
import com.byteq.ai.ragstudio.mcp.service.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

import java.util.List;

/**
 * MCP 客户端自动配置
 * <p>
 * 启动时从数据库异步加载所有已启用的 MCP Server 配置，
 * 委托 {@link DynamicMcpConnectionManager} 建立连接并注册远程工具。
 * 异步设计确保 MCP Server 连接失败或超时不会阻塞应用启动。
 * </p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpClientAutoConfiguration {

    private final McpToolRegistry toolRegistry;
    private final DynamicMcpConnectionManager connectionManager;
    private final McpServerService mcpServerService;

    /**
     * 应用启动时异步加载 MCP Server 连接
     * <p>
     * MCP Server 可能不可达（网络故障、未部署等），
     * 在独立线程中连接避免阻塞 Spring Boot 启动。
     * </p>
     */
    @PostConstruct
    public void init() {
        Thread loader = new Thread(this::loadFromDatabase, "mcp-startup-loader");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 从数据库加载所有已启用的 MCP Server 并建立连接
     */
    private void loadFromDatabase() {
        try {
            List<McpServerDO> dbServers = mcpServerService.listEnabled();
            if (CollUtil.isEmpty(dbServers)) {
                log.info("数据库中无已启用的 MCP Server 配置");
                return;
            }

            log.info("从数据库加载 {} 个 MCP Server 配置", dbServers.size());
            for (McpServerDO server : dbServers) {
                try {
                    DynamicMcpConnectionManager.ConnectResult result = connectionManager.connect(server);
                    // 将连接结果同步到数据库，避免前端显示过期的错误状态
                    mcpServerService.updateConnectionStatus(server.getId(), result);
                } catch (Exception e) {
                    log.warn("MCP Server [{}] 连接失败（不影响应用运行）: {}",
                            server.getName(), e.getMessage());
                }
            }
            log.info("MCP Server 启动加载完成，共处理 {} 个配置", dbServers.size());
        } catch (Exception e) {
            log.error("从数据库加载 MCP Server 配置失败: {}", e.getMessage(), e);
        }
    }
}
