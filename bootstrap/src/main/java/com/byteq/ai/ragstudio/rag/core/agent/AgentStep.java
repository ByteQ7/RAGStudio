package com.byteq.ai.ragstudio.rag.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Agent 单步记录
 * <p>
 * 承载 ReACT 循环中一轮迭代的全部信息：
 * <ol>
 *   <li>Planning 产出 Thought + Action</li>
 *   <li>Action 执行后回填 Observation</li>
 *   <li>Action 为 FINISH 时携带 finalAnswer</li>
 * </ol>
 * 此对象通过 {@code AgentCallback.onAgentStep()} 实时推送至前端。
 */
public class AgentStep {

    /** 迭代轮次（从 0 开始） */
    private final int iteration;

    /** 多步规划（仅在首次迭代时携带） */
    private final String plan;

    /** 规划拆分后的任务列表（仅在首次迭代时携带） */
    private final List<String> planSteps;

    /** 推理内容（Thought） */
    private final String thought;

    /** 本轮动作类型 */
    private final AgentAction action;

    /** 工具名称（action == TOOL_CALL 时非空） */
    private final String toolName;

    /** 工具参数（action == TOOL_CALL 时非空） */
    private final Map<String, Object> toolInput;

    /** 工具执行结果（action == TOOL_CALL 时，执行后回填） */
    private String observation;

    /** 最终回答（action == FINISH 时非空） */
    private final String finalAnswer;

    /** 本步骤耗时（ms） */
    private long durationMs;

    public AgentStep(int iteration, String plan, String thought, AgentAction action,
                     String toolName, Map<String, Object> toolInput,
                     String finalAnswer) {
        this.iteration = iteration;
        this.plan = plan;
        this.planSteps = parsePlanSteps(plan);
        this.thought = thought;
        this.action = action;
        this.toolName = toolName;
        this.toolInput = toolInput;
        this.finalAnswer = finalAnswer;
    }

    /** 创建 TOOL_CALL 步骤（无 plan） */
    public static AgentStep toolCall(int iteration, String thought,
                                     String toolName, Map<String, Object> toolInput) {
        return new AgentStep(iteration, null, thought, AgentAction.TOOL_CALL,
                toolName, toolInput, null);
    }

    /** 创建 FINISH 步骤（无 plan） */
    public static AgentStep finish(int iteration, String thought, String finalAnswer) {
        return new AgentStep(iteration, null, thought, AgentAction.FINISH,
                null, null, finalAnswer);
    }

    /** 创建 TOOL_CALL 步骤（带 plan） */
    public static AgentStep toolCallWithPlan(int iteration, String plan, String thought,
                                             String toolName, Map<String, Object> toolInput) {
        return new AgentStep(iteration, plan, thought, AgentAction.TOOL_CALL,
                toolName, toolInput, null);
    }

    /** 创建 ERROR 步骤 */
    public static AgentStep error(int iteration, String thought, String errorMsg) {
        AgentStep step = new AgentStep(iteration, null, thought, AgentAction.ERROR,
                null, null, null);
        step.observation = errorMsg;
        return step;
    }

    // ==================== getters / setters ====================

    public int getIteration() { return iteration; }
    public String getPlan() { return plan; }
    public String getThought() { return thought; }
    public AgentAction getAction() { return action; }
    public String getToolName() { return toolName; }
    public Map<String, Object> getToolInput() { return toolInput; }
    public String getObservation() { return observation; }
    public void setObservation(String observation) { this.observation = observation; }
    public String getFinalAnswer() { return finalAnswer; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public List<String> getPlanSteps() { return planSteps; }

    /** 按序号拆分 plan 文本为任务列表，支持多步骤在一行（如 "1. A 2. B"） */
    private static final Pattern PLAN_SPLIT_PATTERN = Pattern.compile("\\s*\\d+[.、]\\s*");

    public static List<String> parsePlanSteps(String plan) {
        if (plan == null || plan.isBlank()) return List.of();
        String[] parts = PLAN_SPLIT_PATTERN.split(plan);
        List<String> steps = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                steps.add(trimmed);
            }
        }
        return steps;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AgentStep{iteration=").append(iteration)
                .append(", action=").append(action);
        if (thought != null && !thought.isEmpty()) {
            sb.append(", thought='").append(truncate(thought, 60)).append("'");
        }
        if (toolName != null) {
            sb.append(", toolName='").append(toolName).append("'");
        }
        if (observation != null && !observation.isEmpty()) {
            sb.append(", observation='").append(truncate(observation, 60)).append("'");
        }
        if (finalAnswer != null) {
            sb.append(", finalAnswer='").append(truncate(finalAnswer, 60)).append("'");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
