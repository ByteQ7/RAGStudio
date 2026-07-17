package com.byteq.ai.ragstudio.rag.core.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent 身份卡
 * <p>
 * 描述一个 Agent 的身份、能力、输入输出协议。用于 Agent 发现和路由决策。
 * 每个 {@link SubAgent} 通过 {@link SubAgent#getCard()} 暴露自己的卡片。
 * </p>
 *
 * @param name         Agent 唯一标识（如 "qa"、"tool"）
 * @param description  Agent 能力描述（自然语言，供 LLM 路由决策时阅读）
 * @param capabilities Agent 具备的能力标签列表（如 ["kb_retrieval", "mcp_tools"]）
 * @param inputSchema  输入参数的 JSON Schema 描述（用于 Task.input 校验）
 * @param outputSchema 输出产物的 JSON Schema 描述（用于 Artifact.content 校验）
 */
public record AgentCard(
        String name,
        String description,
        List<String> capabilities,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
) {
    public AgentCard(String name, String description, List<String> capabilities) {
        this(name, description, capabilities, Map.of(), Map.of());
    }
}
