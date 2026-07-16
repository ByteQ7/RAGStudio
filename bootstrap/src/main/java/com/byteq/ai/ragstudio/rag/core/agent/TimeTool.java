package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 内置时间工具
 * <p>
 * 获取全球任意时区的当前日期和时间。不依赖任何外部服务，直接调用 {@link java.time} API，
 * 零网络开销，永不故障。支持 IANA 时区 ID（含夏令时自动处理）。
 * <p>
 * Agent 可用此工具获取当前日期时间，无需依赖外部 MCP 时间服务。
 */
@Slf4j
public class TimeTool implements Tool {

    private static final String TOOL_NAME = "time_now";
    private static final String TOOL_DESCRIPTION =
            "获取指定时区的当前日期和时间，只要用户的问题有关时间日期，都应该先调用本工具获取相关信息。"
            + "timezone 参数使用 IANA 时区 ID（如 Asia/Shanghai、Europe/London、America/New_York）。"
            + "不传 timezone 时默认返回 Asia/Shanghai 时间。"
            + "返回格式为「yyyy年M月d日 HH:mm:ss 时区ID」。";

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
                        "timezone", Map.of(
                                "type", "string",
                                "description", "IANA 时区 ID，如 Asia/Shanghai、Europe/London、"
                                        + "America/New_York、Asia/Tokyo。常见值："
                                        + "Asia/Shanghai（中国）、Europe/London（伦敦）、"
                                        + "America/New_York（纽约）、"
                                        + "America/Los_Angeles（洛杉矶）、"
                                        + "Asia/Tokyo（东京）。不传则默认 Asia/Shanghai。",
                                "default", "Asia/Shanghai"
                        )
                ),
                List.of(),
                null,
                null,
                null
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        try {
            ZoneId zone;
            if (params != null && params.get("timezone") instanceof String tz && StrUtil.isNotBlank(tz)) {
                zone = ZoneId.of(tz);
            } else {
                zone = ZoneId.of("Asia/Shanghai");
            }
            ZonedDateTime now = ZonedDateTime.now(zone);
            String formatted = now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"));
            log.debug("内置时间工具返回: {} ({})", formatted, zone);
            return ToolResult.success(TOOL_NAME,
                    "当前时间：" + formatted + " " + zone.getId());
        } catch (Exception e) {
            log.warn("时区解析失败，使用 Asia/Shanghai 兜底: {}", e.getMessage());
            String now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"));
            return ToolResult.success(TOOL_NAME,
                    "当前时间：" + now + " Asia/Shanghai");
        }
    }
}
