package com.byteq.ai.ragstudio.mcp.service;

import com.byteq.ai.ragstudio.mcp.controller.request.McpServerCreateRequest;
import com.byteq.ai.ragstudio.mcp.controller.request.McpServerUpdateRequest;
import com.byteq.ai.ragstudio.mcp.controller.vo.McpServerVO;
import com.byteq.ai.ragstudio.mcp.dao.entity.McpServerDO;

import java.util.List;

/**
 * MCP Server 管理服务接口
 * <p>
 * 定义外部 MCP Server 配置的完整生命周期管理操作，
 * 包括创建、更新、删除、查询以及与远程 MCP Server 的连接管理。
 * </p>
 */
public interface McpServerService {

    /**
     * 创建 MCP Server 配置
     * <p>创建配置后，如果 enabled=true 则自动建立连接。</p>
     *
     * @param request 创建请求参数
     * @return MCP Server ID
     */
    String create(McpServerCreateRequest request);

    /**
     * 更新 MCP Server 配置
     * <p>更新配置后自动重新连接。</p>
     *
     * @param id      MCP Server ID
     * @param request 更新请求参数
     */
    void update(String id, McpServerUpdateRequest request);

    /**
     * 删除 MCP Server 配置
     * <p>删除前先断开连接，再进行逻辑删除。</p>
     *
     * @param id MCP Server ID
     */
    void delete(String id);

    /**
     * 根据 ID 查询 MCP Server 详情
     *
     * @param id MCP Server ID
     * @return MCP Server 详情
     */
    McpServerVO queryById(String id);

    /**
     * 查询所有 MCP Server 列表
     *
     * @return MCP Server 列表
     */
    List<McpServerVO> listAll();

    /**
     * 启用或禁用 MCP Server
     * <p>启用时自动建立连接，禁用时自动断开连接。</p>
     *
     * @param id      MCP Server ID
     * @param enabled 是否启用
     */
    void toggleEnabled(String id, boolean enabled);

    /**
     * 测试 MCP Server 连接
     *
     * @param id MCP Server ID
     * @return 连接测试结果
     */
    DynamicMcpConnectionManager.HealthStatus testConnection(String id);

    /**
     * 重新加载 MCP Server 连接
     *
     * @param id MCP Server ID
     * @return 重新连接结果
     */
    DynamicMcpConnectionManager.ConnectResult reload(String id);

    /**
     * 更新 MCP Server 连接状态到数据库
     *
     * @param serverId MCP Server ID
     * @param result   连接结果
     */
    void updateConnectionStatus(String serverId, DynamicMcpConnectionManager.ConnectResult result);

    /**
     * 查询所有已启用的 MCP Server（供启动阶段使用）
     *
     * @return 已启用的 MCP Server 列表
     */
    List<McpServerDO> listEnabled();

    /**
     * 根据 ID 获取 MCP Server 实体（供内部使用）
     *
     * @param id MCP Server ID
     * @return MCP Server 实体
     */
    McpServerDO getById(String id);
}
