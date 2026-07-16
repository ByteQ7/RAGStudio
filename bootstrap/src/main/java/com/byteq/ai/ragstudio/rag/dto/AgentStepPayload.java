package com.byteq.ai.ragstudio.rag.dto;

import java.util.List;
import java.util.Map;

/**
 * Agent 推理步骤的 SSE 事件载荷
 * <p>
 * 当 ReACT Agent 循环中产生新的推理步骤时，通过 SSE 的 {@code agent_step} 事件推送给前端。
 * 前端根据 {@link #action} 字段区分展示方式（思考 / 工具调用 / 观察 / 最终回答）。
 *
 * @param iteration    迭代轮次
 * @param action       动作类型：TOOL_CALL / FINISH / ERROR
 * @param thought      推理内容
 * @param toolName     工具名称（TOOL_CALL 时）
 * @param toolInput    工具参数（TOOL_CALL 时）
 * @param observation  工具执行结果（TOOL_CALL 执行后回填）
 * @param finalAnswer  最终回答（FINISH 时）
 * @param durationMs   本步骤耗时
 */
public record AgentStepPayload(
        int iteration,
        String action,
        String plan,
        List<String> planSteps,
        String thought,
        String toolName,
        Map<String, Object> toolInput,
        String observation,
        String finalAnswer,
        long durationMs
) {
    /**
     * 从内部 AgentStep 模型创建 SSE 载荷
     */
    public static AgentStepPayload from(
            com.byteq.ai.ragstudio.rag.core.agent.AgentStep step) {
        return new AgentStepPayload(
                step.getIteration(),
                step.getAction().name(),
                step.getPlan(),
                step.getPlanSteps(),
                step.getThought(),
                step.getToolName(),
                step.getToolInput(),
                step.getObservation(),
                step.getFinalAnswer(),
                step.getDurationMs()
        );
    }

    /**
     * 判断本步骤是否在工具执行后更新（即 observation 已回填）
     */
    public boolean hasObservation() {
        return observation != null && !observation.isEmpty();
    }
}
