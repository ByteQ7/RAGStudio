package com.byteq.ai.ragstudio.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册表默认实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMcpToolRegistry implements McpToolRegistry {

    /**
     * 工具执行器存储
     * key: toolId, value: executor
     */
    private final Map<String, McpToolExecutor> executorMap = new ConcurrentHashMap<>();

    /**
     * Spring 容器中的所有 McpToolExecutor Bean（自动注入）
     */
    private final List<McpToolExecutor> autoDiscoveredExecutors;

    /**
     * 启动时自动注册所有发现的执行器
     */
    @PostConstruct
    public void init() {
        if (CollUtil.isEmpty(autoDiscoveredExecutors)) {
            log.info("MCP 工具注册跳过, 未发现任何工具执行器");
            return;
        }

        for (McpToolExecutor executor : autoDiscoveredExecutors) {
            register(executor);
        }
        log.info("MCP 工具自动注册完成, 共注册 {} 个工具", autoDiscoveredExecutors.size());
    }

    /**
     * 注册工具执行器到注册表，校验执行器和工具ID非空后存入 ConcurrentHashMap，重复注册时覆盖并记录警告
     */
    @Override
    public void register(McpToolExecutor executor) {
        if (executor == null || executor.getToolDefinition() == null) {
            log.warn("尝试注册空的执行器，已忽略");
            return;
        }

        String toolId = executor.getToolId();
        if (StrUtil.isBlank(toolId)) {
            log.warn("工具 ID 为空，已忽略");
            return;
        }

        McpToolExecutor existing = executorMap.put(toolId, executor);
        if (existing != null) {
            log.warn("工具 {} 已存在，已覆盖", toolId);
        } else {
            log.info("MCP 工具注册成功, toolId: {}", toolId);
        }
    }

    /**
     * 根据工具ID从注册表中移除执行器，存在时记录注销日志
     */
    @Override
    public void unregister(String toolId) {
        McpToolExecutor removed = executorMap.remove(toolId);
        if (removed != null) {
            log.info("MCP 工具注销成功, toolId: {}", toolId);
        }
    }

    /**
     * 根据工具ID查找对应的执行器，以 Optional 形式返回
     */
    @Override
    public Optional<McpToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(executorMap.get(toolId));
    }

    /**
     * 收集所有已注册执行器的工具定义，返回 Tool 列表
     */
    @Override
    public List<Tool> listAllTools() {
        return executorMap.values().stream()
                .map(McpToolExecutor::getToolDefinition)
                .toList();
    }

    /**
     * 返回所有已注册执行器的副本列表
     */
    @Override
    public List<McpToolExecutor> listAllExecutors() {
        return new ArrayList<>(executorMap.values());
    }

    /**
     * 判断指定工具ID是否已注册
     */
    @Override
    public boolean contains(String toolId) {
        return executorMap.containsKey(toolId);
    }

    /**
     * 返回已注册工具总数
     */
    @Override
    public int size() {
        return executorMap.size();
    }
}
