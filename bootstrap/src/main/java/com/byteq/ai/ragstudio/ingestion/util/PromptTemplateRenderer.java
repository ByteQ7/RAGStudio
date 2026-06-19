package com.byteq.ai.ragstudio.ingestion.util;

import java.util.Map;

/**
 * 提示词模板渲染器
 */
public final class PromptTemplateRenderer {

    private PromptTemplateRenderer() {
    }

    /**
     * 渲染提示词模板
     * <p>
     * 将模板中的 {@code {{key}}} 占位符替换为对应的变量值。
     * 例如：{@code "请分析以下内容：{{text}}"} 中的 {@code {{text}}} 会被替换为实际文本。
     * </p>
     *
     * @param template  提示词模板字符串，使用 {{key}} 作为占位符
     * @param variables 变量键值对映射
     * @return 替换变量后的提示词字符串
     */
    public static String render(String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) {
            return template;
        }
        String out = template;
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                String key = "{{" + entry.getKey() + "}}";
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                out = out.replace(key, value);
            }
        }
        return out;
    }
}
