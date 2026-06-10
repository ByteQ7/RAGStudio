package com.byteq.ai.ragstudio.infra.util;

import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

/**
 * LLM 响应文本清理工具类
 * <p>
 * 用于清洗 LLM 模型返回的原始文本内容，去除不需要的格式标记和噪音字符。
 * 当前主要功能是去除模型输出中可能包含的 Markdown 代码块围栏，
 * 例如当模型以 ```json ... ``` 格式返回结构化数据时，提取出纯净的 JSON 字符串。
 * </p>
 *
 * <p>典型使用场景：</p>
 * <ul>
 *   <li>模型返回 JSON 格式数据时被包裹在 Markdown 代码块中，需要提取纯净 JSON</li>
 *   <li>对模型输出进行后处理，确保下游解析逻辑不受格式标记干扰</li>
 * </ul>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class LLMResponseCleaner {

    /** 匹配开头的 Markdown 代码块围栏，例如 ```json、```python 等，支持可选的换行符 */
    private static final Pattern LEADING_CODE_FENCE = Pattern.compile("^```[\\w-]*\\s*\\n?");

    /** 匹配结尾的 Markdown 代码块围栏，即 ``` 及末尾的空白字符，支持可选的换行符 */
    private static final Pattern TRAILING_CODE_FENCE = Pattern.compile("\\n?```\\s*$");

    /**
     * 从可能包含非 JSON 文本的响应中提取第一个合法的 JSON 对象字符串。
     * <p>
     * 当 LLM 未严格遵循指令、在 JSON 前后附加了自然语言解释时，此方法能够
     * 定位首个 {@code &#123;} 并依据大括号深度匹配提取出完整的 JSON 对象字符串。
     * 匹配过程中会正确处理字符串字面量内部的大括号以及转义字符。
     * </p>
     *
     * @param text 可能包含 JSON 的原始文本
     * @return 提取到的 JSON 对象字符串；如果未找到合法 JSON 则返回原输入
     */
    public static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();

        // 快速路径：本身就是 JSON 对象
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // 在文本中找到第一个 '{'
        int start = trimmed.indexOf('{');
        if (start < 0) {
            return trimmed;
        }

        // 依据大括号深度匹配，同时跳过字符串字面量中的花括号
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // 跳过转义字符
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                switch (c) {
                    case '"' -> inString = true;
                    case '{' -> depth++;
                    case '}' -> {
                        depth--;
                        if (depth == 0) {
                            return trimmed.substring(start, i + 1);
                        }
                    }
                }
            }
        }
        return trimmed;
    }

    /**
     * 移除原始字符串首尾的 Markdown 代码块围栏标记
     * <p>
     * 当 LLM 以 Markdown 格式返回内容时（例如 ```json ... ```），此方法能够
     * 自动识别并去除开闭代码块围栏，提取出纯净的内容。
     * 处理逻辑：先去除首尾空白，然后依次移除开头和结尾的代码块围栏，最后再次 trim。
     * </p>
     *
     * @param raw 原始响应文本，可能包含 Markdown 代码块围栏
     * @return 处理后的纯净文本；如果传入 null 则返回 null
     */
    public static String stripMarkdownCodeFence(String raw) {
        if (raw == null) {
            return null;
        }
        // 先 trim 去除首尾空白，确保正则匹配准确
        String cleaned = raw.trim();
        // 移除开头的代码块围栏（如 ```json）
        cleaned = LEADING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        // 移除结尾的代码块围栏（如 ```）
        cleaned = TRAILING_CODE_FENCE.matcher(cleaned).replaceFirst("");
        // 再次 trim，去除处理过程中可能暴露的空白字符
        return cleaned.trim();
    }
}
