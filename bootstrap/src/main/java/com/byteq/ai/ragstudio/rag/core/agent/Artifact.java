package com.byteq.ai.ragstudio.rag.core.agent;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 产物
 * <p>
 * Agent 执行 Task 后产出的结果。Artifact 包含执行结果数据，
 * 可由 {@link OrchestratorAgent} 收集并汇总。
 * </p>
 *
 * @param id        Artifact 唯一 ID
 * @param taskId    关联的 Task ID
 * @param agentName 产出该 Artifact 的 Agent 名称
 * @param type      产物类型（如 "answer"、"plan"、"title"、"summary"、"chunks"）
 * @param content   产物内容（Object 类型，按 type 解析）
 * @param metadata  附加元数据（如耗时、状态等）
 */
public record Artifact(
        String id,
        String taskId,
        String agentName,
        String type,
        Object content,
        Map<String, Object> metadata
) {
    public Artifact(String taskId, String agentName, String type, Object content) {
        this(UUID.randomUUID().toString().replace("-", ""),
                taskId, agentName, type, content, Map.of());
    }

    public Artifact(String taskId, String agentName, String type, Object content, Map<String, Object> metadata) {
        this(UUID.randomUUID().toString().replace("-", ""),
                taskId, agentName, type, content, metadata);
    }

    /** 便捷方法：获取字符串类型的内容 */
    public String contentAsString() {
        return content instanceof String s ? s : (content != null ? content.toString() : "");
    }

    @SuppressWarnings("unchecked")
    public <T> T contentAs() {
        return (T) content;
    }
}
