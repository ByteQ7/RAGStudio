package com.byteq.ai.ragstudio.rag.core.agent;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 循环上下文
 * <p>
 * 承载一次 ReACT Agent 调用所需的全部输入和运行时状态。
 * 分为两部分：
 * <ul>
 *   <li><b>不可变输入</b>：用户问题、对话历史、KB 上下文、可用工具、配置参数</li>
 *   <li><b>运行时累积</b>：Agent 步骤列表、消息列表（动态追加 Observation）</li>
 * </ul>
 */
public class AgentContext {

    // ==================== 不可变输入 ====================

    /** 用户原始问题 */
    private final String question;

    /** 对话历史（含摘要，由 Memory Module 产出） */
    private final List<ChatMessage> history;

    /** 预检索的 KB 上下文文本（相关性判断为"相关"时非空，否则为空字符串） */
    private final String kbContext;

    /** 知识库相关性判断结果（true=已检索且有关，false=判断为无关或未选知识库） */
    private final boolean kbRelevant;

    /** 可用工具列表 */
    private final List<Tool> tools;

    /** 最大迭代次数（默认 10） */
    private final int maxIterations;

    /** 总超时时间（ms，默认 120_000） */
    private final long timeoutMs;

    // ==================== 运行时累积 ====================

    /** Agent 推理步骤记录（用于前端回放和日志） */
    private final List<AgentStep> steps = new ArrayList<>();

    /** 发送给 LLM 的消息列表（动态追加 Observation） */
    private final List<ChatMessage> messages = new ArrayList<>();

    /** 循环开始时间戳 */
    private long startTimeMs;

    public AgentContext(String question, List<ChatMessage> history,
                        String kbContext, boolean kbRelevant,
                        List<Tool> tools, int maxIterations, long timeoutMs) {
        this.question = question;
        this.history = history != null ? List.copyOf(history) : List.of();
        this.kbContext = kbContext != null ? kbContext : "";
        this.kbRelevant = kbRelevant;
        this.tools = tools != null ? List.copyOf(tools) : List.of();
        this.maxIterations = maxIterations > 0 ? maxIterations : 10;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000L;
    }

    /** 标记循环开始 */
    public void markStart() {
        this.startTimeMs = System.currentTimeMillis();
    }

    /** 检查是否超时 */
    public boolean isTimedOut() {
        return System.currentTimeMillis() - startTimeMs > timeoutMs;
    }

    /** 记录 Agent 步骤 */
    public void addStep(AgentStep step) {
        steps.add(step);
    }

    /** 向消息列表追加消息 */
    public void addMessage(ChatMessage message) {
        messages.add(message);
    }

    // ==================== getters ====================

    public String getQuestion() { return question; }
    public List<ChatMessage> getHistory() { return history; }
    public String getKbContext() { return kbContext; }
    public boolean isKbRelevant() { return kbRelevant; }
    public List<Tool> getTools() { return tools; }
    public int getMaxIterations() { return maxIterations; }
    public long getTimeoutMs() { return timeoutMs; }
    public List<AgentStep> getSteps() { return List.copyOf(steps); }
    public List<ChatMessage> getMessages() { return messages; }
    public long getStartTimeMs() { return startTimeMs; }

    /** 是否包含 KB 上下文 */
    public boolean hasKb() {
        return kbContext != null && !kbContext.isEmpty();
    }

    /** 是否有可用工具 */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    @Override
    public String toString() {
        return "AgentContext{question='" + truncate(question, 50)
                + "', kbRelevant=" + kbRelevant
                + ", tools=" + tools.size()
                + ", maxIterations=" + maxIterations
                + ", steps=" + steps.size() + "}";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
