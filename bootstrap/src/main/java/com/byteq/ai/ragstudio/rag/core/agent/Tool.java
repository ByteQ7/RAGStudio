package com.byteq.ai.ragstudio.rag.core.agent;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.util.Map;

/**
 * 统一工具接口
 * <p>
 * 定义 Agent 可调用的工具的标准契约。所有工具——无论是 MCP 远程工具、
 * 本地 RAG 检索、还是未来自定义工具——都通过此接口接入 Agent。
 * <p>
 * 与现有 {@code com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor} 的区别：
 * <ul>
 *   <li>McpToolExecutor：MCP 协议专用，返回 CallToolResult</li>
 *   <li>Tool：通用抽象，返回统一的 ToolResult，不绑定 MCP 协议</li>
 * </ul>
 * McpToolAdapter 负责桥接二者。
 */
public interface Tool {

    /**
     * 工具唯一名称
     * <p>
     * LLM 在 Action 字段中通过此名称指定要调用的工具。
     * 名称应简洁、无空格、语义明确（如 "weather_query"、"rag_search"）。
     *
     * @return 工具名称
     */
    String name();

    /**
     * 工具功能描述
     * <p>
     * 注入到 System Prompt 中，帮助 LLM 理解工具的用途和使用场景。
     *
     * @return 工具描述文本
     */
    String description();

    /**
     * 参数定义（JSON Schema 格式）
     * <p>
     * 描述工具接受的参数及其类型、必填性、默认值等。
     * 兼容 MCP 协议的 {@link JsonSchema} 格式。
     * 无参数时返回空 Schema（properties 为空 Map）。
     *
     * @return 参数 Schema
     */
    JsonSchema inputSchema();

    /**
     * 执行工具
     * <p>
     * 接收参数映射并返回统一的执行结果。
     * 实现应处理异常，不向上抛出——错误以 {@link ToolResult#isSuccess()} = false 表示。
     *
     * @param params 工具参数（key 为参数名，value 为参数值）
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> params);
}
