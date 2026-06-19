package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * MCP 工具适配器
 * <p>
 * 将现有的 {@code McpClientToolExecutor}（MCP 协议专用，返回 {@link CallToolResult}）
 * 适配为统一的 {@link Tool} 接口（返回 {@link ToolResult}）。
 * <p>
 * 适配逻辑：
 * <ol>
 *   <li>从 McpClientToolExecutor 获取工具定义（name、description、inputSchema）</li>
 *   <li>execute() 代理到 McpClientToolExecutor.execute()</li>
 *   <li>将 CallToolResult 转换为 ToolResult（文本和图片内容合并为字符串）</li>
 * </ol>
 */
@Slf4j
public class McpToolAdapter implements Tool {

    private final com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor executor;
    private final io.modelcontextprotocol.spec.McpSchema.Tool mcpTool;

    /**
     * @param executor 现有 MCP 工具执行器（通常为 {@code McpClientToolExecutor}）
     */
    public McpToolAdapter(com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor executor) {
        this.executor = executor;
        this.mcpTool = executor.getToolDefinition();
    }

    @Override
    public String name() {
        return mcpTool.name();
    }

    @Override
    public String description() {
        return mcpTool.description();
    }

    @Override
    public JsonSchema inputSchema() {
        return mcpTool.inputSchema();
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            CallToolResult result = executor.execute(params != null ? params : Map.of());
            String content = formatCallToolResult(result);
            if (result != null && result.isError() != null && result.isError()) {
                return ToolResult.failure(name(), content);
            }
            return ToolResult.success(name(), content);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("MCP 工具 {} 执行异常: {}", name(), errorMsg);
            return ToolResult.failure(name(), errorMsg);
        }
    }

    /**
     * 将 MCP CallToolResult 格式化为可读文本
     * <p>
     * 包含文本内容和 base64 图片引用的 Markdown 格式。
     */
    private String formatCallToolResult(CallToolResult result) {
        if (result == null || CollUtil.isEmpty(result.content())) {
            return "（无返回内容）";
        }
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof TextContent textContent) {
                sb.append(textContent.text());
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n");
                }
            } else if (content instanceof ImageContent imageContent) {
                String mimeType = imageContent.mimeType() != null
                        ? imageContent.mimeType() : "image/png";
                sb.append("[图片: data:").append(mimeType)
                        .append(";base64,")
                        .append(imageContent.data() != null
                                ? imageContent.data().substring(0, Math.min(50, imageContent.data().length())) + "..."
                                : "")
                        .append("]\n");
            }
        }
        return sb.toString().trim();
    }
}
