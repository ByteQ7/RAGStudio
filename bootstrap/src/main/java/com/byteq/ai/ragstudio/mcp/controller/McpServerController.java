package com.byteq.ai.ragstudio.mcp.controller;

import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.mcp.controller.request.McpServerCreateRequest;
import com.byteq.ai.ragstudio.mcp.controller.request.McpServerUpdateRequest;
import com.byteq.ai.ragstudio.mcp.controller.vo.McpServerVO;
import com.byteq.ai.ragstudio.mcp.service.DynamicMcpConnectionManager;
import com.byteq.ai.ragstudio.mcp.service.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP Server 管理控制器
 * <p>
 * 提供外部 MCP Server 的动态管理接口，支持增删改查、启用/禁用、连接测试和重新加载等操作。
 * 所有接口统一返回 {@link Result} 格式的响应体。
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;

    /**
     * 查询 MCP Server 列表
     *
     * @return 所有 MCP Server 列表
     */
    @GetMapping("/mcp-server")
    public Result<List<McpServerVO>> list() {
        return Results.success(mcpServerService.listAll());
    }

    /**
     * 查询 MCP Server 详情
     *
     * @param id MCP Server ID
     * @return MCP Server 详情
     */
    @GetMapping("/mcp-server/{id}")
    public Result<McpServerVO> queryById(@PathVariable("id") String id) {
        return Results.success(mcpServerService.queryById(id));
    }

    /**
     * 创建 MCP Server
     *
     * @param request 创建请求参数
     * @return 新创建的 MCP Server ID
     */
    @PostMapping("/mcp-server")
    public Result<String> create(@RequestBody McpServerCreateRequest request) {
        return Results.success(mcpServerService.create(request));
    }

    /**
     * 更新 MCP Server
     *
     * @param id      MCP Server ID
     * @param request 更新请求参数
     * @return 空成功响应
     */
    @PutMapping("/mcp-server/{id}")
    public Result<Void> update(@PathVariable("id") String id,
                               @RequestBody McpServerUpdateRequest request) {
        mcpServerService.update(id, request);
        return Results.success();
    }

    /**
     * 删除 MCP Server
     *
     * @param id MCP Server ID
     * @return 空成功响应
     */
    @DeleteMapping("/mcp-server/{id}")
    public Result<Void> delete(@PathVariable("id") String id) {
        mcpServerService.delete(id);
        return Results.success();
    }

    /**
     * 启用或禁用 MCP Server
     *
     * @param id      MCP Server ID
     * @param enabled 是否启用
     * @return 空成功响应
     */
    @PutMapping("/mcp-server/{id}/toggle")
    public Result<Void> toggleEnabled(@PathVariable("id") String id,
                                      @RequestParam("enabled") boolean enabled) {
        mcpServerService.toggleEnabled(id, enabled);
        return Results.success();
    }

    /**
     * 测试 MCP Server 连接
     *
     * @param id MCP Server ID
     * @return 健康检查结果
     */
    @PostMapping("/mcp-server/{id}/test")
    public Result<DynamicMcpConnectionManager.HealthStatus> testConnection(@PathVariable("id") String id) {
        return Results.success(mcpServerService.testConnection(id));
    }

    /**
     * 重新加载 MCP Server 连接
     *
     * @param id MCP Server ID
     * @return 重新连接结果
     */
    @PostMapping("/mcp-server/{id}/reload")
    public Result<DynamicMcpConnectionManager.ConnectResult> reload(@PathVariable("id") String id) {
        return Results.success(mcpServerService.reload(id));
    }
}
