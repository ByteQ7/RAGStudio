package com.byteq.ai.ragstudio.mcp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.mcp.controller.request.McpServerCreateRequest;
import com.byteq.ai.ragstudio.mcp.controller.request.McpServerUpdateRequest;
import com.byteq.ai.ragstudio.mcp.controller.vo.McpServerVO;
import com.byteq.ai.ragstudio.mcp.dao.entity.McpServerDO;
import com.byteq.ai.ragstudio.mcp.dao.mapper.McpServerMapper;
import com.byteq.ai.ragstudio.mcp.service.DynamicMcpConnectionManager;
import com.byteq.ai.ragstudio.mcp.service.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP Server 管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerServiceImpl implements McpServerService {

    private final McpServerMapper mcpServerMapper;
    private final DynamicMcpConnectionManager connectionManager;

    @Override
    public String create(McpServerCreateRequest request) {
        // 参数校验
        if (StrUtil.isBlank(request.getName())) {
            throw new ClientException("服务名称不能为空");
        }
        if (StrUtil.isBlank(request.getUrl())) {
            throw new ClientException("服务地址不能为空");
        }

        // 名称唯一性校验
        Long count = mcpServerMapper.selectCount(
                new LambdaQueryWrapper<McpServerDO>()
                        .eq(McpServerDO::getName, request.getName())
        );
        if (count > 0) {
            throw new ServiceException("MCP Server 名称已存在：" + request.getName());
        }

        // 构建实体
        McpServerDO server = McpServerDO.builder()
                .name(request.getName().trim())
                .url(request.getUrl().trim())
                .description(request.getDescription())
                .enabled(request.getEnabled() != null ? request.getEnabled() : 1)
                .transportType(StrUtil.isNotBlank(request.getTransportType()) ? request.getTransportType() : null)
                .headers(request.getHeaders())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();

        mcpServerMapper.insert(server);
        log.info("创建 MCP Server 配置: id={}, name={}, url={}", server.getId(), server.getName(), server.getUrl());

        // 如果启用，自动建立连接
        if (server.getEnabled() == 1) {
            DynamicMcpConnectionManager.ConnectResult result = connectionManager.connect(server);
            updateConnectionStatus(server.getId(), result);
        }

        return server.getId();
    }

    @Override
    public void update(String id, McpServerUpdateRequest request) {
        McpServerDO existing = mcpServerMapper.selectById(id);
        if (existing == null) {
            throw new ClientException("MCP Server 不存在：" + id);
        }

        // 名称唯一性校验（排除自身）
        if (StrUtil.isNotBlank(request.getName()) && !request.getName().equals(existing.getName())) {
            Long count = mcpServerMapper.selectCount(
                    new LambdaQueryWrapper<McpServerDO>()
                            .eq(McpServerDO::getName, request.getName())
                            .ne(McpServerDO::getId, id)
            );
            if (count > 0) {
                throw new ServiceException("MCP Server 名称已存在：" + request.getName());
            }
        }

        // 更新字段
        boolean needReconnect = false;
        if (StrUtil.isNotBlank(request.getName())) {
            existing.setName(request.getName().trim());
        }
        if (StrUtil.isNotBlank(request.getUrl()) && !request.getUrl().equals(existing.getUrl())) {
            existing.setUrl(request.getUrl().trim());
            needReconnect = true;
        }
        if (request.getDescription() != null) {
            existing.setDescription(request.getDescription());
        }
        if (request.getEnabled() != null) {
            existing.setEnabled(request.getEnabled());
            needReconnect = true;
        }
        if (StrUtil.isNotBlank(request.getTransportType())) {
            existing.setTransportType(request.getTransportType());
            needReconnect = true;
        }
        if (request.getHeaders() != null) {
            existing.setHeaders(request.getHeaders());
            needReconnect = true;
        }
        existing.setUpdatedBy(UserContext.getUsername());

        mcpServerMapper.updateById(existing);
        log.info("更新 MCP Server 配置: id={}, needReconnect={}", id, needReconnect);

        // 只要服务器处于启用状态，更新后一律重连（确保连接状态与 DB 同步，清除历史错误）
        if (existing.getEnabled() == 1) {
            DynamicMcpConnectionManager.ConnectResult result = connectionManager.reconnect(existing);
            updateConnectionStatus(id, result);
        } else if (needReconnect) {
            connectionManager.disconnect(id);
        }
    }

    @Override
    public void delete(String id) {
        McpServerDO existing = mcpServerMapper.selectById(id);
        if (existing == null) {
            throw new ClientException("MCP Server 不存在：" + id);
        }

        // 先断开连接
        connectionManager.disconnect(id);

        // 物理清理同名已删除记录，避免唯一约束 uk_mcp_server_name(name, deleted) 冲突
        mcpServerMapper.physicalDeleteByName(existing.getName());

        // 逻辑删除
        mcpServerMapper.deleteById(id);
        log.info("删除 MCP Server: id={}, name={}", id, existing.getName());
    }

    @Override
    public McpServerVO queryById(String id) {
        McpServerDO server = mcpServerMapper.selectById(id);
        if (server == null) {
            throw new ClientException("MCP Server 不存在：" + id);
        }
        return toVO(server);
    }

    @Override
    public List<McpServerVO> listAll() {
        List<McpServerDO> servers = mcpServerMapper.selectList(
                new LambdaQueryWrapper<McpServerDO>()
                        .orderByDesc(McpServerDO::getCreateTime)
        );
        return servers.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public void toggleEnabled(String id, boolean enabled) {
        McpServerDO server = mcpServerMapper.selectById(id);
        if (server == null) {
            throw new ClientException("MCP Server 不存在：" + id);
        }

        server.setEnabled(enabled ? 1 : 0);
        server.setUpdatedBy(UserContext.getUsername());
        mcpServerMapper.updateById(server);

        if (enabled) {
            DynamicMcpConnectionManager.ConnectResult result = connectionManager.connect(server);
            updateConnectionStatus(id, result);
            log.info("启用 MCP Server: id={}, name={}", id, server.getName());
        } else {
            connectionManager.disconnect(id);
            updateDisconnectedStatus(id);
            log.info("禁用 MCP Server: id={}, name={}", id, server.getName());
        }
    }

    @Override
    public DynamicMcpConnectionManager.HealthStatus testConnection(String id) {
        McpServerDO server = mcpServerMapper.selectById(id);
        if (server == null) {
            throw new ClientException("MCP Server 不存在：" + id);
        }

        DynamicMcpConnectionManager.HealthStatus status = connectionManager.healthCheck(id);

        // 更新状态到数据库
        server.setLastStatus(status.status());
        server.setLastError(status.error());
        server.setLastCheckTime(new Date());
        mcpServerMapper.updateById(server);

        return status;
    }

    @Override
    public DynamicMcpConnectionManager.ConnectResult reload(String id) {
        McpServerDO server = mcpServerMapper.selectById(id);
        if (server == null) {
            throw new ClientException("MCP Server 不存在：" + id);
        }

        DynamicMcpConnectionManager.ConnectResult result = connectionManager.reconnect(server);
        updateConnectionStatus(id, result);
        return result;
    }

    @Override
    public List<McpServerDO> listEnabled() {
        return mcpServerMapper.selectList(
                new LambdaQueryWrapper<McpServerDO>()
                        .eq(McpServerDO::getEnabled, 1)
        );
    }

    @Override
    public McpServerDO getById(String id) {
        return mcpServerMapper.selectById(id);
    }

    // ==================== 内部方法 ====================

    private McpServerVO toVO(McpServerDO server) {
        McpServerVO vo = BeanUtil.copyProperties(server, McpServerVO.class);
        // 运行时数据：从连接管理器获取工具数量和实时状态
        String serverId = server.getId();
        vo.setToolCount(connectionManager.getToolCount(serverId));
        // 优先使用运行时的实时状态，并保持 lastStatus 与 lastError 的一致性
        String runtimeStatus = connectionManager.getStatus(serverId);
        if (StrUtil.isNotBlank(runtimeStatus) && !"disconnected".equals(runtimeStatus)) {
            vo.setLastStatus(runtimeStatus);
            if ("connected".equals(runtimeStatus)) {
                vo.setLastError(null);
            }
        }
        return vo;
    }

    @Override
    public void updateConnectionStatus(String serverId, DynamicMcpConnectionManager.ConnectResult result) {
        McpServerDO server = mcpServerMapper.selectById(serverId);
        if (server == null) return;

        if (result.success()) {
            server.setLastStatus("connected");
            server.setLastError(null);
        } else {
            server.setLastStatus("error");
            server.setLastError(result.error());
        }
        server.setLastCheckTime(new Date());
        mcpServerMapper.updateById(server);
    }

    private void updateDisconnectedStatus(String serverId) {
        McpServerDO server = mcpServerMapper.selectById(serverId);
        if (server == null) return;

        server.setLastStatus("disconnected");
        server.setLastError(null);
        server.setLastCheckTime(new Date());
        mcpServerMapper.updateById(server);
    }
}
