package com.byteq.ai.ragstudio.rag.core.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Map;

/**
 * MCP 参数提取器接口
 */
public interface McpParameterExtractor {

    /**
     * 从用户问题中提取 MCP 工具所需的参数
     *
     * @param userQuestion 用户原始问题
     * @param tool         MCP 工具定义（McpSchema.Tool）
     * @return 提取到的参数键值对
     */
    Map<String, Object> extractParameters(String userQuestion, Tool tool);

    /**
     * 从用户问题中提取 MCP 工具所需的参数（支持自定义提示词）
     *
     * @param userQuestion         用户原始问题
     * @param tool                 MCP 工具定义（McpSchema.Tool）
     * @param customPromptTemplate 自定义参数提取提示词模板（可选，为空则使用默认提示词）
     * @return 提取到的参数键值对
     */
    default Map<String, Object> extractParameters(String userQuestion, Tool tool, String customPromptTemplate) {
        return extractParameters(userQuestion, tool);
    }
}
