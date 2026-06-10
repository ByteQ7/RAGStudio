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
 * 启动时从数据库加载所有已启用的 MCP Server 配置，
 * 委托 {@link DynamicMcpConnectionManager} 建立连接并注册远程工具。
 * MCP Server 的增删改查统一通过管理后台 API 操作，运行时即时生效。
 * </p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpClientAutoConfiguration {

    private final McpToolRegistry toolRegistry;
    private final DynamicMcpConnectionManager connectionManager;
    private final McpServerService mcpServerService;

    @PostConstruct
    public void init() {
        loadFromDatabase();
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
                    log.error("MCP Server [{}] 连接失败: {}", server.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("从数据库加载 MCP Server 配置失败: {}", e.getMessage(), e);
        }
    }
}
