package com.byteq.ai.ragstudio.rag.core.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Optional;

/**
 * MCP 工具注册表接口
 */
public interface McpToolRegistry {

    /**
     * 注册工具执行器
     *
     * @param executor 工具执行器
     */
    void register(McpToolExecutor executor);

    /**
     * 注销工具
     *
     * @param toolId 工具 ID
     */
    void unregister(String toolId);

    /**
     * 根据工具 ID 获取执行器
     *
     * @param toolId 工具 ID
     * @return 工具执行器（可能不存在）
     */
    Optional<McpToolExecutor> getExecutor(String toolId);

    /**
     * 获取所有已注册的工具定义
     *
     * @return 工具定义列表
     */
    List<Tool> listAllTools();

    /**
     * 获取所有已注册的工具执行器
     *
     * @return 执行器列表
     */
    List<McpToolExecutor> listAllExecutors();

    /**
     * 检查工具是否已注册
     *
     * @param toolId 工具 ID
     * @return 是否已注册
     */
    boolean contains(String toolId);

    /**
     * 获取已注册工具数量
     *
     * @return 工具数量
     */
    int size();
}
