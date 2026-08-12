package com.byteq.ai.ragstudio.rag.core.tool;

/**
 * 工具执行结果
 * <p>
 * 封装工具调用的成功/失败状态、结果内容和执行耗时。
 * 通过 {@link #toObservation()} 格式化为 LLM 可理解的 Observation 文本，
 * 注入回 Agent 循环的消息上下文。
 */
public class ToolResult {

    private final String toolName;
    private final boolean success;
    private final String content;
    private String toolCallId;
    private long durationMs;
    private java.util.List<String> imageUrls;
    private java.util.List<String> s3ImageUrls;

    public ToolResult(String toolName, boolean success, String content) {
        this.toolName = toolName;
        this.success = success;
        this.content = content != null ? content : "";
    }

    public static ToolResult success(String toolName, String content) {
        return new ToolResult(toolName, true, content);
    }

    public static ToolResult success(String toolName, String content, java.util.List<String> imageUrls) {
        ToolResult result = new ToolResult(toolName, true, content);
        result.imageUrls = imageUrls;
        return result;
    }

    public static ToolResult failure(String toolName, String error) {
        return new ToolResult(toolName, false, error);
    }

    public String getToolName() { return toolName; }
    public boolean isSuccess() { return success; }
    public String getContent() { return content; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public java.util.List<String> getImageUrls() { return imageUrls; }
    public java.util.List<String> getS3ImageUrls() { return s3ImageUrls; }
    public void setS3ImageUrls(java.util.List<String> urls) { this.s3ImageUrls = urls; }

    public String toObservation() {
        StringBuilder sb = new StringBuilder();
        sb.append("Observation: ");
        if (success) {
            sb.append("工具 [").append(toolName).append("] 执行成功");
            if (durationMs > 0) sb.append(" (").append(durationMs).append("ms)");
            sb.append(":\n").append(content);
        } else {
            sb.append("工具 [").append(toolName).append("] 执行失败: ").append(content);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ToolResult{tool='" + toolName + "', success=" + success
                + ", durationMs=" + durationMs + ", content='" + truncate(content, 80) + "'}";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
