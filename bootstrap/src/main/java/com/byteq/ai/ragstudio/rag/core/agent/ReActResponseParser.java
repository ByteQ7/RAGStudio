package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ReACT 响应解析器
 * <p>
 * LLM 输出 JSON 结构化格式，解析为 {@link AgentStep}。
 * Level 1: 直接解析 JSON
 * Level 2: 正则兜底（兼容不遵循 JSON 格式的旧模型）
 * </p>
 */
@Slf4j
@Component
public class ReActResponseParser {

    private static final Gson GSON = new Gson();

    public AgentStep parse(String raw, int iteration) {
        if (StrUtil.isBlank(raw)) {
            return AgentStep.finish(iteration, "", "（模型未返回内容）");
        }

        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw).trim();

        // === Level 1: JSON 解析 ===
        AgentStep step = tryJsonParse(cleaned, iteration);
        if (step != null) return step;

        // === Level 2: 文本正则兜底 ===
        step = tryTextParse(cleaned, iteration);
        if (step != null) return step;

        // === Level 3: 兜底 — 尝试从疑似 JSON 中提取 final_answer ===
        String fallback = extractFallbackAnswer(cleaned);
        log.info("LLM 未遵循 ReACT 格式，兜底处理。内容长度: {}", cleaned.length());
        return AgentStep.finish(iteration, "", fallback);
    }

    /**
     * 兜底提取：如果文本是 JSON 但有解析问题，尝试直接提取 final_answer
     */
    private String extractFallbackAnswer(String text) {
        if (text.startsWith("{")) {
            try {
                JsonElement el = JsonParser.parseString(text);
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    String finalAnswer = getString(obj, "final_answer");
                    if (StrUtil.isNotBlank(finalAnswer)) {
                        return finalAnswer;
                    }
                }
            } catch (Exception ignored) { }
            // JSON 解析失败，用基本的正则提取
            var m = java.util.regex.Pattern.compile("\"final_answer\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(text);
            if (m.find()) {
                String extracted = m.group(1);
                // 反转义 JSON 中的 \n, \", \\
                String unescaped = extracted
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
                if (StrUtil.isNotBlank(unescaped)) {
                    return unescaped;
                }
            }
        }
        return text;
    }

    // ==================== Level 1: JSON ====================

    private AgentStep tryJsonParse(String text, int iteration) {
        String json = extractJson(text);
        if (json == null) return null;

        AgentStep step = parseJsonObject(json, iteration);
        if (step != null) return step;

        // Gson 解析失败，尝试修复 thought/final_answer 字段中嵌入的 ASCII 双引号
        String repaired = repairEmbeddedQuotes(json);
        if (repaired != null) {
            step = parseJsonObject(repaired, iteration);
            if (step != null) {
                log.debug("JSON 修复成功，thought 中嵌入了未转义的双引号");
                return step;
            }
        }

        return null;
    }

    private AgentStep parseJsonObject(String json, int iteration) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String thought = getString(obj, "thought");
            String plan = getString(obj, "plan");
            String action = getString(obj, "action");

            if (StrUtil.isBlank(action)) {
                // 无 action 字段：JSON 格式不完整，不猜测意图，返回 null 让文本兜底处理
                return null;
            }

            if ("finish".equalsIgnoreCase(action) || "FINISH".equalsIgnoreCase(action)) {
                String finalAnswer = getString(obj, "final_answer");
                // 空 final_answer 不返回 null（避免落入 Level 3 把原始 JSON 当正文）
                return AgentStep.finish(iteration, thought, StrUtil.isNotBlank(finalAnswer) ? finalAnswer : "");
            }

            // TOOL_CALL
            Map<String, Object> toolInput = new LinkedHashMap<>();
            JsonElement input = obj.get("action_input");
            if (input != null && input.isJsonObject()) {
                input.getAsJsonObject().entrySet().forEach(e -> {
                    JsonElement v = e.getValue();
                    if (v.isJsonPrimitive()) {
                        var p = v.getAsJsonPrimitive();
                        if (p.isNumber()) { toolInput.put(e.getKey(), p.getAsDouble()); }
                        else if (p.isBoolean()) { toolInput.put(e.getKey(), p.getAsBoolean()); }
                        else { toolInput.put(e.getKey(), p.getAsString()); }
                    } else if (v.isJsonObject()) {
                        toolInput.put(e.getKey(), GSON.fromJson(v, LinkedHashMap.class));
                    }
                });
            }
            if (!plan.isEmpty()) {
                return AgentStep.toolCallWithPlan(iteration, plan, thought, action, toolInput);
            }
            return AgentStep.toolCall(iteration, thought, action, toolInput);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 修复 JSON 字符串字段（如 thought、final_answer）中嵌入的 ASCII 双引号。
     * <p>
     * LLM 有时会在字符串值中直接使用 " 引用用户原句，导致 JSON 解析失败。
     * 利用结构化字段（如 action、action_input）作为边界，定位并转义问题引号。
     * </p>
     */
    private String repairEmbeddedQuotes(String json) {
        // 已知的结构化字段边界特征（不含前导逗号，避免重建时重复）
        String[] structuralPatterns = {
                "\"action\": \"", "\"action\":\"",
                "\"plan\": \"", "\"plan\":\"",
                "\"action_input\":"
        };

        for (String boundary : structuralPatterns) {
            int boundaryPos = json.indexOf(boundary);
            if (boundaryPos < 0) continue;

            // 检查是否在 key: 之前的 thought/final_answer 字段中有问题引号
            // 用反引号(Backward)从 boundary 位置往前扫描，找到 "thought": " 或 "final_answer": " 开头
            int thoughtStart = json.lastIndexOf("\"thought\"", boundaryPos);
            int answerStart = json.lastIndexOf("\"final_answer\"", boundaryPos);
            int planStart = json.lastIndexOf("\"plan\"", boundaryPos);
            int targetStart = Math.max(Math.max(thoughtStart, answerStart), planStart);
            if (targetStart < 0) continue;

            // 找到 ": " 之后的值的起始位置
            int colonPos = json.indexOf(':', targetStart);
            if (colonPos < 0) continue;
            int quoteStart = json.indexOf('"', colonPos + 1);
            if (quoteStart < 0 || quoteStart >= boundaryPos) continue;

            // 提取值内容（从 quoteStart+1 到 boundaryPos）
            String rawValue = json.substring(quoteStart + 1, boundaryPos);
            // 移除值尾部可能存在的 ", （JSON 字段分隔符）
            int trailing = rawValue.length();
            while (trailing > 0 && (rawValue.charAt(trailing - 1) == ' ' || rawValue.charAt(trailing - 1) == ',')) {
                trailing--;
            }
            if (trailing > 0 && rawValue.charAt(trailing - 1) == '"') {
                // 去掉尾部闭合引号（如果存在的话，有些情况可能没有）
                trailing--;
            }
            rawValue = rawValue.substring(0, trailing);

            // 转义值内部的 ASCII 双引号
            String escaped = rawValue.replace("\"", "\\\"");

            if (escaped.equals(rawValue)) continue; // 没有变化，无需修复

            log.debug("修复 JSON 字符串字段: 转义 {} 个双引号", 
                    rawValue.length() - escaped.replace("\\\"", "").length());

            // 重建 JSON：前缀 + 转义后的值 + 后缀
            String prefix = json.substring(0, quoteStart);
            // 提取 `"` 闭合符 + 字段分隔符 "," 之后的后缀
            String suffix = json.substring(boundaryPos);
            return prefix + "\"" + escaped + "\", " + suffix;
        }
        return null;
    }

    private String extractJson(String text) {
        // 先尝试整体解析
        if (text.startsWith("{")) {
            try {
                JsonParser.parseString(text);
                return text;
            } catch (JsonSyntaxException ignored) { }
        }
        // 从文本中提取 JSON 块
        return LLMResponseCleaner.extractJson(text);
    }

    // ==================== Level 2: 文本正则兜底 ====================

    private AgentStep tryTextParse(String text, int iteration) {
        // 复用旧版正则逻辑作为兜底
        var p = java.util.regex.Pattern.compile(
                "(?:^|\\n)\\s*Action\\s*[:：]\\s*(\\S+)",
                java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE);
        var m = p.matcher(text);
        if (!m.find()) return null;

        String actionName = m.group(1).trim();
        String thought = extractTextThought(text);
        String plan = extractTextPlan(text);

        if ("finish".equalsIgnoreCase(actionName) || "FINISH".equalsIgnoreCase(actionName)) {
            String finalAnswer = extractAfterTag(text, m.end());
            var fa = java.util.regex.Pattern.compile(
                    "Final\\s*Answer\\s*[:：]\\s*(.*)",
                    java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (fa.find()) { finalAnswer = fa.group(1).trim(); }
            if (!plan.isEmpty()) {
                return new AgentStep(iteration, plan, thought, AgentAction.FINISH, null, null, finalAnswer);
            }
            return AgentStep.finish(iteration, thought, finalAnswer);
        }

        // TOOL_CALL
        Map<String, Object> toolInput = extractTextActionInput(text);
        if (toolInput.isEmpty()) {
            String after = extractAfterTag(text, m.end());
            toolInput = extractJsonFromText(after);
        }
        if (!plan.isEmpty()) {
            return AgentStep.toolCallWithPlan(iteration, plan, thought, actionName, toolInput);
        }
        return AgentStep.toolCall(iteration, thought, actionName, toolInput);
    }

    private String extractTextThought(String text) {
        var p = java.util.regex.Pattern.compile(
                "Thought\\s*[:：]\\s*(.*?)(?=\\n\\s*(?:Action\\s*[:：]|Observation|Final|$)|\\s+Action\\s*[:：])",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
        var m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private String extractTextPlan(String text) {
        var p = java.util.regex.Pattern.compile(
                "Plan\\s*[:：]\\s*(.*?)(?=\\n\\s*(?:Thought|Action|Observation|Final|$))",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
        var m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    private String extractAfterTag(String text, int start) {
        if (start >= text.length()) return "";
        String after = text.substring(start);
        int nl = after.indexOf('\n');
        return nl >= 0 ? after.substring(nl + 1).trim() : "";
    }

    private Map<String, Object> extractTextActionInput(String text) {
        var p = java.util.regex.Pattern.compile("Action\\s*Input\\s*[:：]\\s*", java.util.regex.Pattern.CASE_INSENSITIVE);
        var m = p.matcher(text);
        if (!m.find()) return Map.of();
        String after = text.substring(m.end()).trim();
        after = LLMResponseCleaner.stripMarkdownCodeFence(after);
        String json = LLMResponseCleaner.extractJson(after);
        if (json != null && json.startsWith("{")) {
            return parseJsonParams(json);
        }
        return Map.of();
    }

    private Map<String, Object> extractJsonFromText(String text) {
        if (StrUtil.isBlank(text)) return Map.of();
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(text);
        String json = LLMResponseCleaner.extractJson(cleaned);
        return (json != null && json.startsWith("{")) ? parseJsonParams(json) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonParams(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return Map.of();
            Map<String, Object> params = new LinkedHashMap<>();
            var obj = el.getAsJsonObject();
            for (String key : obj.keySet()) {
                JsonElement v = obj.get(key);
                if (v.isJsonPrimitive()) {
                    var p = v.getAsJsonPrimitive();
                    if (p.isNumber()) {
                        double d = p.getAsDouble();
                        params.put(key, d == Math.floor(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : d);
                    } else if (p.isBoolean()) { params.put(key, p.getAsBoolean()); }
                    else { params.put(key, p.getAsString()); }
                } else if (v.isJsonArray()) { params.put(key, GSON.fromJson(v, java.util.List.class)); }
                else if (v.isJsonObject()) { params.put(key, GSON.fromJson(v, LinkedHashMap.class)); }
            }
            return params;
        } catch (JsonSyntaxException e) { return Map.of(); }
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : "";
    }
}
