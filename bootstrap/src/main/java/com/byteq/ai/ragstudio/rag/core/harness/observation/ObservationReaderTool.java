package com.byteq.ai.ragstudio.rag.core.harness.observation;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 观察回读工具（Observation Mask）
 * <p>
 * Agent 凭上下文中压缩观察给出的句柄（如 {@code obs_2}）按需回读完整结果：
 * full（全文）/ grep（关键词匹配行）/ range（字符区间）。回读结果不登记为新观察，
 * 单次返回受 max_chars 限流，避免把刚省下的上下文又读回来。
 */
public class ObservationReaderTool implements Tool {

    public static final String TOOL_NAME = "observation_reader";

    private static final String TOOL_DESCRIPTION =
            "按句柄回读被压缩的工具观察完整结果。上下文中的旧工具结果可能显示为「[观察已压缩 obs_N] ... 结论」；"
            + "当结论已足够回答时不要调用本工具，只有在需要完整原文、精确细节或核对引用时才回读。"
            + "支持 mode: full 读取全文（默认）/ grep 按关键词查找匹配行 / range 按字符区间读取。";

    private static final String MODE_FULL = "full";
    private static final String MODE_GREP = "grep";
    private static final String MODE_RANGE = "range";

    private final ObservationStore store;
    private final ObservationMaskProperties properties;

    public ObservationReaderTool(ObservationStore store, ObservationMaskProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(
                "object",
                Map.of(
                        "handle", Map.of(
                                "type", "string",
                                "description", "观察句柄，如 obs_1（见被压缩观察的标记）"
                        ),
                        "mode", Map.of(
                                "type", "string",
                                "description", "读取模式：full 全文（默认）/ grep 关键词匹配行 / range 字符区间",
                                "enum", List.of(MODE_FULL, MODE_GREP, MODE_RANGE)
                        ),
                        "query", Map.of(
                                "type", "string",
                                "description", "grep 模式必填：要查找的关键词"
                        ),
                        "offset", Map.of(
                                "type", "integer",
                                "description", "range 模式必填 / full 模式可选：起始字符位置（从 0 开始）",
                                "default", 0
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "range 模式必填：读取的最大字符数"
                        ),
                        "max_chars", Map.of(
                                "type", "integer",
                                "description", "本次返回的最大字符数（受服务端上限约束）",
                                "default", properties.getReaderDefaultChars()
                        )
                ),
                List.of("handle"),
                null,
                null,
                null
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String handle = params != null && params.get("handle") instanceof String h ? h.trim() : "";
        if (StrUtil.isBlank(handle)) {
            return ToolResult.failure(TOOL_NAME, "缺少必填参数: handle（如 obs_1）");
        }
        ObservationEntry entry = store.findByHandle(handle);
        if (entry == null) {
            List<String> available = store.availableHandles();
            return ToolResult.failure(TOOL_NAME, "句柄不存在: " + handle
                    + (available.isEmpty() ? "。当前没有可回读的观察。" : "。当前可回读: " + String.join(", ", available)));
        }

        String mode = params.get("mode") instanceof String m ? m.trim().toLowerCase() : MODE_FULL;
        int maxChars = resolveMaxChars(params);
        ToolResult result = switch (mode) {
            case MODE_GREP -> grep(entry, params, maxChars);
            case MODE_RANGE -> range(entry, params, maxChars);
            default -> full(entry, params, maxChars);
        };
        if (result.isSuccess()) {
            store.recordReaderCall();
        }
        return result;
    }

    private ToolResult full(ObservationEntry entry, Map<String, Object> params, int maxChars) {
        int offset = resolveInt(params, "offset", 0);
        return readRange(entry, offset, maxChars, "全文回读");
    }

    private ToolResult range(ObservationEntry entry, Map<String, Object> params, int maxChars) {
        if (!params.containsKey("offset") || !(params.get("offset") instanceof Number)) {
            return ToolResult.failure(TOOL_NAME, "range 模式缺少必填参数: offset（起始字符位置，从 0 开始）");
        }
        if (!params.containsKey("limit") || !(params.get("limit") instanceof Number limitNum)) {
            return ToolResult.failure(TOOL_NAME, "range 模式缺少必填参数: limit（读取字符数）");
        }
        int offset = Math.max(0, ((Number) params.get("offset")).intValue());
        int limit = Math.min(Math.max(1, limitNum.intValue()), maxChars);
        return readRange(entry, offset, limit, "区间回读");
    }

    private ToolResult readRange(ObservationEntry entry, int offset, int length, String label) {
        String text = entry.getFullText();
        if (offset >= text.length()) {
            return ToolResult.failure(TOOL_NAME, "offset " + offset + " 超出范围（完整结果共 "
                    + text.length() + " 字），请使用 0 到 " + Math.max(0, text.length() - 1) + " 之间的起点");
        }
        int end = Math.min(text.length(), offset + length);
        String content = text.substring(offset, end);
        StringBuilder sb = new StringBuilder();
        sb.append("=== 观察 ").append(entry.getHandle())
                .append(" | 工具 ").append(entry.getToolName())
                .append(" | ").append(label)
                .append(" | 完整结果 ").append(text.length()).append(" 字 ===\n")
                .append(content);
        if (end < text.length()) {
            sb.append("\n（本次显示第 ").append(offset).append('-').append(end)
                    .append(" 字，剩余 ").append(text.length() - end)
                    .append(" 字；可继续用 observation_reader(handle=\"")
                    .append(entry.getHandle()).append("\", mode=\"range\", offset=")
                    .append(end).append(", limit=").append(length).append(") 读取）");
        }
        return ToolResult.success(TOOL_NAME, sb.toString());
    }

    private ToolResult grep(ObservationEntry entry, Map<String, Object> params, int maxChars) {
        String query = params.get("query") instanceof String q ? q.trim() : "";
        if (StrUtil.isBlank(query)) {
            return ToolResult.failure(TOOL_NAME, "grep 模式缺少必填参数: query（关键词）");
        }
        String text = entry.getFullText();
        String[] lines = text.split("\n", -1);
        String needle = query.toLowerCase();
        List<String> matched = new ArrayList<>();
        int matchCount = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(needle)) {
                matchCount++;
                matched.add((i + 1) + ": " + lines[i]);
            }
        }
        if (matchCount == 0) {
            return ToolResult.success(TOOL_NAME, "观察 " + entry.getHandle()
                    + " 中未找到包含 \"" + query + "\" 的内容（完整结果共 " + text.length() + " 字）。"
                    + "可尝试更短的关键词，或用 mode=\"full\" 分段读取。");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 观察 ").append(entry.getHandle())
                .append(" | 工具 ").append(entry.getToolName())
                .append(" | 关键词 \"").append(query).append("\" 匹配 ").append(matchCount)
                .append(" 行（完整结果 ").append(text.length()).append(" 字）===\n");
        int used = sb.length();
        int shown = 0;
        for (String line : matched) {
            if (used + line.length() + 1 > maxChars) {
                break;
            }
            sb.append(line).append('\n');
            used += line.length() + 1;
            shown++;
        }
        if (shown < matchCount) {
            sb.append("（仅显示前 ").append(shown).append(" 行，共 ").append(matchCount)
                    .append(" 行匹配；可缩小关键词或用 range 模式精读）");
        }
        return ToolResult.success(TOOL_NAME, sb.toString());
    }

    private int resolveMaxChars(Map<String, Object> params) {
        int configured = params != null && params.get("max_chars") instanceof Number n
                ? n.intValue()
                : properties.getReaderDefaultChars();
        int max = properties.getReaderMaxChars();
        return Math.min(Math.max(1, configured), max);
    }

    private int resolveInt(Map<String, Object> params, String key, int defaultValue) {
        if (params != null && params.get(key) instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        return defaultValue;
    }
}
