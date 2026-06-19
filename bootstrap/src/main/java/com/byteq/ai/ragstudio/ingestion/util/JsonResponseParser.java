package com.byteq.ai.ragstudio.ingestion.util;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 响应解析器，用于解析 LLM 返回的 JSON 字符串
 */
public final class JsonResponseParser {

    private static final Gson GSON = new Gson();

    private JsonResponseParser() {
    }

    /**
     * 将 LLM 返回的 JSON 字符串解析为字符串列表
     * <p>
     * 自动处理 LLM 输出中常见的 Markdown 代码块包裹和多余文本，提取 JSON 数组部分。
     * </p>
     *
     * @param raw LLM 返回的原始响应字符串
     * @return 解析后的字符串列表，解析失败返回空列表
     */
    public static List<String> parseStringList(String raw) {
        JsonElement element = parseJsonElement(raw);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        return GSON.fromJson(element, List.class);
    }

    /**
     * 将 LLM 返回的 JSON 字符串解析为键值对 Map
     * <p>
     * 自动处理 LLM 输出中常见的 Markdown 代码块包裹和多余文本，提取 JSON 对象部分。
     * </p>
     *
     * @param raw LLM 返回的原始响应字符串
     * @return 解析后的键值对映射，解析失败返回空 Map
     */
    public static Map<String, Object> parseObject(String raw) {
        JsonElement element = parseJsonElement(raw);
        if (element == null || !element.isJsonObject()) {
            return Collections.emptyMap();
        }
        return GSON.fromJson(element, LinkedHashMap.class);
    }

    // 清理 LLM 输出并解析为 JsonElement，支持去除 Markdown 代码块和提取 JSON 主体
    private static JsonElement parseJsonElement(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        String trimmed = extractJsonBody(cleaned);
        try {
            return JsonParser.parseString(trimmed);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    // 从混合文本中提取 JSON 对象或数组部分，定位首尾的 {} 或 [] 边界
    private static String extractJsonBody(String raw) {
        int objStart = raw.indexOf('{');
        int arrStart = raw.indexOf('[');
        int start;
        if (objStart < 0) {
            start = arrStart;
        } else if (arrStart < 0) {
            start = objStart;
        } else {
            start = Math.min(objStart, arrStart);
        }
        if (start < 0) {
            return raw;
        }
        int objEnd = raw.lastIndexOf('}');
        int arrEnd = raw.lastIndexOf(']');
        int end = Math.max(objEnd, arrEnd);
        if (end < 0 || end <= start) {
            return raw.substring(start);
        }
        return raw.substring(start, end + 1);
    }
}
