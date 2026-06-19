package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReACT 响应解析器
 * <p>
 * 将 LLM 的原始响应文本解析为结构化的 {@link AgentStep}。
 * 采用三级降级策略确保健壮性：
 * <ol>
 *   <li><b>Level 1 — 严格格式</b>：按 Thought / Action / Action Input / Final Answer 字段逐行解析</li>
 *   <li><b>Level 2 — 宽松正则</b>：用正则表达式提取各字段，兼容 Markdown 代码块包裹的 JSON</li>
 *   <li><b>Level 3 — 降级 FINISH</b>：无法解析时，将整个响应视为最终回答</li>
 * </ol>
 */
@Slf4j
@Component
public class ReActResponseParser {

    private static final Gson GSON = new Gson();

    // Level 1: 严格格式的行模式
    private static final Pattern THOUGHT_PATTERN = Pattern.compile(
            "Thought\\s*[:：]\\s*(.*?)(?=\\n\\s*(?:Action|Observation|Final|$))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAN_PATTERN = Pattern.compile(
            "Plan\\s*[:：]\\s*(.*?)(?=\\n\\s*(?:Thought|Action|Observation|Final|$))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action\\s*[:：]\\s*(\\S+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile(
            "Action\\s*Input\\s*[:：]\\s*(\\{.*?(?:\\n.*?)*?\\})\\s*$",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile(
            "Final\\s*Answer\\s*[:：]\\s*(.*)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // Level 2 fallback: loose JSON extraction
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "\\{[^{}]*\\}",
            Pattern.DOTALL);

    /**
     * 解析 LLM 响应为 AgentStep
     *
     * @param raw       LLM 原始响应文本
     * @param iteration 当前迭代轮次
     * @return 解析后的 AgentStep，永远不会返回 null
     */
    public AgentStep parse(String raw, int iteration) {
        if (StrUtil.isBlank(raw)) {
            log.warn("LLM 返回空响应，降级为 FINISH");
            return AgentStep.finish(iteration, "", "（模型未返回内容）");
        }

        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw).trim();

        // === Level 1: 严格格式解析 ===
        AgentStep step = tryStrictParse(cleaned, iteration);
        if (step != null) {
            return step;
        }

        // === Level 2: 宽松正则解析 ===
        step = tryLooseParse(cleaned, iteration);
        if (step != null) {
            return step;
        }

        // === Level 3: 降级为 FINISH ===
        log.warn("无法解析 ReACT 响应格式，降级为 FINISH。响应前200字符: {}",
                cleaned.substring(0, Math.min(200, cleaned.length())));
        return AgentStep.finish(iteration, "", cleaned);
    }

    /**
     * Level 1: 严格格式解析
     * <p>
     * 期望格式：
     * <pre>
     * Thought: 推理内容...
     * Action: tool_name
     * Action Input: {"key": "value"}
     * </pre>
     * 或:
     * <pre>
     * Thought: 推理内容...
     * Action: FINISH
     * Final Answer: 回答内容...
     * </pre>
     */
    private AgentStep tryStrictParse(String text, int iteration) {
        // 检查是否包含 Action 字段
        Matcher actionMatcher = ACTION_PATTERN.matcher(text);
        if (!actionMatcher.find()) {
            return null;
        }
        String actionName = actionMatcher.group(1).trim();

        // 提取 Plan（可选，仅在首次迭代时携带）
        String plan = "";
        Matcher planMatcher = PLAN_PATTERN.matcher(text);
        if (planMatcher.find()) {
            plan = planMatcher.group(1).trim();
        }

        // 提取 Thought
        String thought = "";
        Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(text);
        if (thoughtMatcher.find()) {
            thought = thoughtMatcher.group(1).trim();
        }

        // FINISH 分支
        if ("FINISH".equalsIgnoreCase(actionName)) {
            String finalAnswer = extractAfter(text, actionMatcher.end());
            // 尝试提取显式的 Final Answer 字段
            Matcher faMatcher = FINAL_ANSWER_PATTERN.matcher(text);
            if (faMatcher.find()) {
                finalAnswer = faMatcher.group(1).trim();
            }
            // 无 plan 时用普通 finish
            if (plan.isEmpty()) {
                return AgentStep.finish(iteration, thought, finalAnswer);
            }
            return new AgentStep(iteration, plan, thought, AgentAction.FINISH, null, null, finalAnswer);
        }

        // TOOL_CALL 分支
        Map<String, Object> toolInput = Map.of();
        Matcher inputMatcher = ACTION_INPUT_PATTERN.matcher(text);
        if (inputMatcher.find()) {
            String jsonStr = inputMatcher.group(1).trim();
            // 清理可能的 markdown 代码块包裹
            jsonStr = LLMResponseCleaner.stripMarkdownCodeFence(jsonStr);
            toolInput = parseJsonParams(jsonStr, actionName);
        } else {
            // 尝试在 Action 行后查找 JSON
            String afterAction = extractAfter(text, actionMatcher.end());
            toolInput = extractJsonFromText(afterAction, actionName);
        }

        if (!plan.isEmpty()) {
            return AgentStep.toolCallWithPlan(iteration, plan, thought, actionName, toolInput);
        }
        return AgentStep.toolCall(iteration, thought, actionName, toolInput);
    }

    /**
     * Level 2: 宽松正则解析
     * <p>
     * 当 Level 1 失败时，用更宽松的规则尝试：
     * - 只要文本中包含 Action: xxx 就尝试解析
     * - JSON 可以跨行、被 markdown 包裹
     */
    private AgentStep tryLooseParse(String text, int iteration) {
        // 查找 Action 声明（允许前后有无关文本）
        Matcher actionMatcher = Pattern.compile(
                "Action\\s*[:：]\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!actionMatcher.find()) {
            return null;
        }
        String actionName = actionMatcher.group(1).trim();

        // 提取 Thought（如果有）
        String thought = "";
        Matcher thoughtMatcher = Pattern.compile(
                "(?:Thought|思考)\\s*[:：]\\s*(.+?)(?=\\n\\s*(?:Action|Observation|Final|$))",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(text);
        if (thoughtMatcher.find()) {
            thought = thoughtMatcher.group(1).trim();
        }

        // FINISH
        if ("FINISH".equalsIgnoreCase(actionName)) {
            String finalAnswer = extractAfter(text, actionMatcher.end());
            Matcher faMatcher = FINAL_ANSWER_PATTERN.matcher(text);
            if (faMatcher.find()) {
                finalAnswer = faMatcher.group(1).trim();
            }
            return AgentStep.finish(iteration, thought, finalAnswer);
        }

        // TOOL_CALL — 宽松 JSON 提取
        Map<String, Object> toolInput = Map.of();
        String afterAction = extractAfter(text, actionMatcher.end());
        toolInput = extractJsonFromText(afterAction, actionName);

        return AgentStep.toolCall(iteration, thought, actionName, toolInput);
    }

    /**
     * 从文本中提取 JSON 参数
     */
    private Map<String, Object> extractJsonFromText(String text, String toolName) {
        if (StrUtil.isBlank(text)) {
            return Map.of();
        }
        // 先清理 markdown 代码块
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(text);
        // 尝试找最外层的 JSON 对象
        Matcher jsonMatcher = JSON_BLOCK_PATTERN.matcher(cleaned);
        if (jsonMatcher.find()) {
            return parseJsonParams(jsonMatcher.group(), toolName);
        }
        return Map.of();
    }

    /**
     * 安全解析 JSON 参数字符串
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonParams(String jsonStr, String toolName) {
        if (StrUtil.isBlank(jsonStr)) {
            return Map.of();
        }
        try {
            JsonElement element = JsonParser.parseString(jsonStr);
            if (element.isJsonObject()) {
                Map<String, Object> params = new LinkedHashMap<>();
                var obj = element.getAsJsonObject();
                for (String key : obj.keySet()) {
                    JsonElement value = obj.get(key);
                    if (value.isJsonPrimitive()) {
                        var primitive = value.getAsJsonPrimitive();
                        if (primitive.isNumber()) {
                            double d = primitive.getAsDouble();
                            if (d == Math.floor(d) && !Double.isInfinite(d)
                                    && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                                params.put(key, (int) d);
                            } else {
                                params.put(key, d);
                            }
                        } else if (primitive.isBoolean()) {
                            params.put(key, primitive.getAsBoolean());
                        } else {
                            params.put(key, primitive.getAsString());
                        }
                    } else if (value.isJsonArray()) {
                        params.put(key, GSON.fromJson(value, java.util.List.class));
                    } else if (value.isJsonObject()) {
                        params.put(key, GSON.fromJson(value, LinkedHashMap.class));
                    }
                }
                return params;
            }
        } catch (JsonSyntaxException e) {
            log.debug("JSON 解析失败 for tool={}: {}", toolName, e.getMessage());
        }
        return Map.of();
    }

    /**
     * 从指定位置之后提取剩余文本
     */
    private String extractAfter(String text, int startIndex) {
        if (startIndex >= text.length()) {
            return "";
        }
        String after = text.substring(startIndex).trim();
        // 跳过 Action 行可能剩余的空白和换行
        int firstNewline = after.indexOf('\n');
        if (firstNewline > 0) {
            String firstLine = after.substring(0, firstNewline).trim();
            // 如果第一行只是 Action 的值（如 "weather_query"），跳过它
            after = after.substring(firstNewline).trim();
        }
        return after;
    }
}
