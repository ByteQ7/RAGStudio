package com.byteq.ai.ragstudio.rag.core.agent;

import java.util.Map;
import java.util.UUID;

/**
 * Agent 任务
 * <p>
 * 在 A2A 架构中，任务（Task）是 Agent 之间传递的工作单元。
 * {@link OrchestratorAgent} 创建 Task 并投递给目标 {@link SubAgent}，
 * SubAgent 执行完成后产出 {@link Artifact}。
 * </p>
 */
public class Task {

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    /** 任务唯一 ID */
    private final String id;

    /** 目标 Agent 名称 */
    private final String targetAgent;

    /** 任务输入参数 */
    private final Map<String, Object> input;

    /** 任务状态 */
    private Status status;

    /** 父任务 ID（用于跟踪任务层级） */
    private final String parentTaskId;

    /** 错误信息（FAILED 时） */
    private String errorMessage;

    public Task(String targetAgent, Map<String, Object> input) {
        this(UUID.randomUUID().toString().replace("-", ""), targetAgent, input, null);
    }

    public Task(String id, String targetAgent, Map<String, Object> input, String parentTaskId) {
        this.id = id;
        this.targetAgent = targetAgent;
        this.input = input != null ? Map.copyOf(input) : Map.of();
        this.status = Status.PENDING;
        this.parentTaskId = parentTaskId;
    }

    public String getId() { return id; }
    public String getTargetAgent() { return targetAgent; }
    public Map<String, Object> getInput() { return input; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getParentTaskId() { return parentTaskId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    /** 便捷方法：从 input 中获取字符串参数 */
    public String getInputString(String key) {
        Object val = input.get(key);
        return val instanceof String s ? s : null;
    }

    /** 便捷方法：从 input 中获取字符串列表参数 */
    @SuppressWarnings("unchecked")
    public <T> T getInputAs(String key) {
        return (T) input.get(key);
    }

    @Override
    public String toString() {
        return "Task{id='" + id + "', target='" + targetAgent + "', status=" + status + "}";
    }
}
