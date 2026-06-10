package com.byteq.ai.ragstudio.infra.chat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.NoArgsConstructor;

/**
 * OpenAI 协议风格 SSE 解析器
 * <p>
 * 解析符合 OpenAI Chat Completions API 规范的 SSE（Server-Sent Events）数据流。
 * 支持从 delta（流式）和 message（非流式）两种响应结构中提取文本内容，
 * 并可选的 reasoning_content 字段（用于 DeepSeek-R1 等推理模型）。
 * <p>
 * 设计意图：
 * - 将 SSE 行解析逻辑集中管理，避免在多个 ChatClient 实现中重复解析
 * - 支持流式模式下的 delta 增量解析和非流式模式下的 message 完整解析
 * - reasoning_content 为可选解析开关，由调用方决定是否启用
 * <p>
 * 解析流程：
 * 1. 去除 "data:" 前缀，获取 JSON 负载
 * 2. 检测 "[DONE]" 终止标记
 * 3. 从 JSON 的 choices[0] 中提取 content / reasoning_content / finish_reason
 * <p>
 * 线程安全：解析过程不持有状态，是纯函数，线程安全。
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class OpenAIStyleSseParser {

    /** SSE data 字段前缀 */
    private static final String DATA_PREFIX = "data:";
    /** 流式结束标记 */
    private static final String DONE_MARKER = "[DONE]";

    /**
     * 解析一行 SSE 数据，提取内容与完成状态
     * <p>
     * 处理以下三类输入：
     * - 普通 data 行：解析 JSON 提取 content / reasoning / finish_reason
     * - [DONE] 行：标记流式结束
     * - 空行/非 data 行：返回空事件
     *
     * @param line            原始 SSE 行文本
     * @param gson            Gson 反序列化实例
     * @param reasoningEnabled 是否开启 reasoning_content 解析
     * @return 解析后的事件对象，包含内容、思考内容和完成标记
     */
    static ParsedEvent parseLine(String line, Gson gson, boolean reasoningEnabled) {
        if (line == null || line.isBlank()) {
            return ParsedEvent.empty();
        }

        // 去除 "data:" 前缀，获取纯 JSON 负载
        String payload = line.trim();
        if (payload.startsWith(DATA_PREFIX)) {
            payload = payload.substring(DATA_PREFIX.length()).trim();
        }
        // 检测流式结束标记
        if (DONE_MARKER.equalsIgnoreCase(payload)) {
            return ParsedEvent.done();
        }

        JsonObject obj = gson.fromJson(payload, JsonObject.class);
        JsonArray choices = obj.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return ParsedEvent.empty();
        }

        JsonObject choice0 = choices.get(0).getAsJsonObject();
        // 从 delta（流式）或 message（非流式）中提取 content
        String content = extractText(choice0, "content");
        // 条件解析 reasoning_content：只有开启后才解析，避免不必要的性能开销
        String reasoning = reasoningEnabled ? extractText(choice0, "reasoning_content") : null;
        // 检测 finish_reason 字段，判断是否为最后一个事件
        boolean completed = hasFinishReason(choice0);

        return new ParsedEvent(content, reasoning, completed);
    }

    /**
     * 判断 choice 是否包含 finish_reason 标记
     * <p>
     * finish_reason 存在且非 null 时表示流式输出结束。
     * 常见的取值：stop（正常结束）、length（token 数超限）、content_filter（内容过滤）。
     */
    private static boolean hasFinishReason(JsonObject choice) {
        if (choice == null || !choice.has("finish_reason")) {
            return false;
        }
        JsonElement finishReason = choice.get("finish_reason");
        return finishReason != null && !finishReason.isJsonNull();
    }

    /**
     * 从 choice 对象中提取指定字段的文本内容
     * <p>
     * 兼容两种响应格式：
     * - 流式（stream=true）：内容在 delta 对象中
     * - 非流式（stream=false）：内容在 message 对象中
     * <p>
     * 优先从 delta 中提取，若无 delta 则尝试从 message 中提取。
     *
     * @param choice    choices[0] 的 JSON 对象
     * @param fieldName 字段名，如 "content" 或 "reasoning_content"
     * @return 提取的文本内容，不存在时为 null
     */
    private static String extractText(JsonObject choice, String fieldName) {
        if (choice == null) {
            return null;
        }
        // 流式模式：从 delta 中提取
        if (choice.has("delta") && choice.get("delta").isJsonObject()) {
            JsonObject delta = choice.getAsJsonObject("delta");
            if (delta.has(fieldName)) {
                JsonElement value = delta.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        // 非流式模式：从 message 中提取
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            JsonObject message = choice.getAsJsonObject("message");
            if (message.has(fieldName)) {
                JsonElement value = message.get(fieldName);
                if (value != null && !value.isJsonNull()) {
                    return value.getAsString();
                }
            }
        }
        return null;
    }

    /**
     * 解析后的事件记录
     * <p>
     * 封装一次 SSE 事件解析结果：
     * - content：当前片段的增量文本
     * - reasoning：当前片段的思考过程文本（如 DeepSeek-R1 的 reasoning_content）
     * - completed：是否为最后一个事件（含 finish_reason）
     */
    record ParsedEvent(String content, String reasoning, boolean completed) {

        static ParsedEvent empty() {
            return new ParsedEvent(null, null, false);
        }

        static ParsedEvent done() {
            return new ParsedEvent(null, null, true);
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }
    }
}
