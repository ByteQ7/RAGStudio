package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 工具注册中心
 * <p>
 * 统一管理 Agent 可调用的所有工具。提供注册、查找、执行和格式化能力。
 * 不同于 {@code com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry}（MCP 专用），
 * 本注册中心面向 Agent，接受任何 {@link Tool} 接口的实现。
 * <p>
 * 线程安全：基于 {@link ConcurrentHashMap} 的读写操作。
 */
@Slf4j
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** 单次工具调用的超时时间（默认 30 秒） */
    private Duration toolTimeout = Duration.ofSeconds(30);

    /**
     * 设置工具执行超时
     */
    public void setToolTimeout(Duration timeout) {
        this.toolTimeout = timeout;
    }

    /**
     * 注册工具
     *
     * @param tool 工具实例
     * @throws IllegalArgumentException 名称为空时抛出
     */
    public void register(Tool tool) {
        if (tool == null) {
            log.warn("尝试注册空工具，已忽略");
            return;
        }
        String name = tool.name();
        if (StrUtil.isBlank(name)) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        Tool existing = tools.put(name, tool);
        if (existing != null) {
            log.warn("工具 {} 已存在，已覆盖", name);
        } else {
            log.info("工具注册成功: {}", name);
        }
    }

    /**
     * 批量注册工具
     */
    public void registerAll(List<Tool> toolList) {
        if (CollUtil.isEmpty(toolList)) {
            return;
        }
        for (Tool tool : toolList) {
            register(tool);
        }
    }

    /**
     * 注销工具
     *
     * @param name 工具名称
     */
    public void unregister(String name) {
        Tool removed = tools.remove(name);
        if (removed != null) {
            log.info("工具注销成功: {}", name);
        }
    }

    /**
     * 按名称获取工具
     *
     * @param name 工具名称
     * @return 工具实例（Optional），不存在时为空
     */
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 列出所有已注册工具
     *
     * @return 工具列表（不可变副本）
     */
    public List<Tool> listAll() {
        return List.copyOf(tools.values());
    }

    /**
     * 判断工具是否已注册
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * 获取已注册工具数量
     */
    public int size() {
        return tools.size();
    }

    /**
     * 执行工具
     * <p>
     * 流程：查找 → 执行 → 设置耗时 → 返回。
     * 工具不存在或执行异常时返回失败结果，不向上抛出异常。
     *
     * @param name   工具名称
     * @param params 调用参数
     * @return 执行结果
     */
    public ToolResult execute(String name, Map<String, Object> params) {
        Tool tool = get(name).orElse(null);
        if (tool == null) {
            log.warn("工具不存在: {}", name);
            return ToolResult.failure(name, "未知工具: " + name);
        }

        long start = System.currentTimeMillis();
        try {
            ToolResult result = CompletableFuture
                    .supplyAsync(() -> tool.execute(params != null ? params : Map.of()))
                    .get(toolTimeout.toMillis(), TimeUnit.MILLISECONDS);
            result.setDurationMs(System.currentTimeMillis() - start);
            log.info("工具执行完成: {}, success={}, durationMs={}",
                    name, result.isSuccess(), result.getDurationMs());
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("工具执行超时: {}, timeoutMs={}", name, toolTimeout.toMillis());
            ToolResult failure = ToolResult.failure(name, "工具执行超时（" + toolTimeout.toMillis() + "ms）");
            failure.setDurationMs(duration);
            return failure;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("工具执行异常: {}, durationMs={}, error={}", name, duration, errorMsg);
            ToolResult failure = ToolResult.failure(name, errorMsg);
            failure.setDurationMs(duration);
            return failure;
        }
    }

    /**
     * 格式化工具列表为 System Prompt 文本
     * <p>
     * 生成 LLM 可理解的工具描述，包含名称、描述和参数定义。
     */
    @SuppressWarnings("unchecked")
    public String formatForSystemPrompt() {
        if (tools.isEmpty()) {
            return "当前没有可用工具。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用工具\n\n");
        for (Tool tool : tools.values()) {
            sb.append("### ").append(tool.name()).append("\n");
            if (StrUtil.isNotBlank(tool.description())) {
                sb.append("描述: ").append(tool.description()).append("\n");
            }

            JsonSchema schema = tool.inputSchema();
            if (schema != null && schema.properties() != null && !schema.properties().isEmpty()) {
                List<String> requiredList = schema.required() != null
                        ? schema.required() : List.of();
                sb.append("参数:\n");
                for (Map.Entry<String, Object> entry : schema.properties().entrySet()) {
                    String paramName = entry.getKey();
                    Map<String, Object> propDef = (Map<String, Object>) entry.getValue();

                    String type = propDef.getOrDefault("type", "string").toString();
                    boolean required = requiredList.contains(paramName);
                    String desc = propDef.getOrDefault("description", "").toString();

                    sb.append("  - ").append(paramName)
                            .append(" (").append(type)
                            .append(required ? ", 必填" : ", 可选").append(")");
                    if (StrUtil.isNotBlank(desc)) {
                        sb.append(": ").append(desc);
                    }
                    Object defaultValue = propDef.get("default");
                    if (defaultValue != null) {
                        sb.append(" [默认值: ").append(defaultValue).append("]");
                    }
                    Object enumValues = propDef.get("enum");
                    if (enumValues instanceof List<?> enumList && !enumList.isEmpty()) {
                        String enumStr = enumList.stream().map(Object::toString)
                                .collect(java.util.stream.Collectors.joining(", "));
                        sb.append(" [可选值: ").append(enumStr).append("]");
                    }
                    sb.append("\n");
                }
            } else {
                sb.append("参数: 无\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 清空所有已注册工具
     */
    public void clear() {
        tools.clear();
    }

    /**
     * 获取所有工具名称
     */
    public List<String> listNames() {
        return new ArrayList<>(tools.keySet());
    }
}
