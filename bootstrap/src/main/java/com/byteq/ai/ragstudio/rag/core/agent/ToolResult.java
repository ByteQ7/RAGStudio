package com.byteq.ai.ragstudio.rag.core.agent;

/**
 * 工具执行结果
 * <p>
 * 封装工具调用的成功/失败状态、结果内容和执行耗时。
 * 通过 {@link #toObservation()} 格式化为 LLM 可理解的 Observation 文本，
 * 注入回 Agent 循环的消息上下文。
 */
public class ToolResult {

    /** 工具名称 */
    private final String toolName;

    /** 是否执行成功 */
    private final boolean success;

    /** 结果文本（成功时为核心内容，失败时为错误描述） */
    private final String content;

    /** 执行耗时（ms） */
    private long durationMs;

    public ToolResult(String toolName, boolean success, String content) {
        this.toolName = toolName;
        this.success = success;
        this.content = content != null ? content : "";
    }

    /** 创建成功结果 */
    public static ToolResult success(String toolName, String content) {
        return new ToolResult(toolName, true, content);
    }

    /** 创建失败结果 */
    public static ToolResult failure(String toolName, String error) {
        return new ToolResult(toolName, false, error);
    }

    /** 格式化为 Observation 文本，注入 LLM 上下文 */
    public String toObservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("Observation: ");
        if (success) {
            sb.append("工具 [").append(toolName).append("] 执行成功");
            if (durationMs > 0) {
                sb.append(" (").append(durationMs).append("ms)");
            }
            sb.append(":\n").append(content);
        } else {
            sb.append("工具 [").append(toolName).append("] 执行失败: ").append(content);
        }
        return sb.toString();
    }

    // ==================== getters / setters ====================

    public String getToolName() { return toolName; }
    public boolean isSuccess() { return success; }
    public String getContent() { return content; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    @Override
    public String toString() {
        return "ToolResult{tool='" + toolName + "', success=" + success
                + ", durationMs=" + durationMs
                + ", content='" + truncate(content, 80) + "'}";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
