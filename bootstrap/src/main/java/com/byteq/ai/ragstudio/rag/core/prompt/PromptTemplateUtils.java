package com.byteq.ai.ragstudio.rag.core.prompt;

import cn.hutool.core.util.StrUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板工具类
 * <p>
 * 提供模板占位符填充、连续空行清理、section 分隔解析等文本处理能力，
 * 被 {@link PromptTemplateLoader} 和 {@link RAGPromptService} 调用。
 * </p>
 */
public final class PromptTemplateUtils {

    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("(\\n){3,}");
    private static final Pattern SECTION_HEADER = Pattern.compile("^---\\s*section:\\s*(\\S+)\\s*---$", Pattern.MULTILINE);

    /**
     * 清理提示词文本，将连续三个及以上的换行缩减为两个，并去除首尾空白
     *
     * @param prompt 原始提示词文本
     * @return 清理后的提示词文本
     */
    public static String cleanupPrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        return MULTI_BLANK_LINES.matcher(prompt).replaceAll("\n\n").trim();
    }

    /**
     * 将模板中的 {key} 占位符替换为对应的值
     *
     * @param template 模板字符串
     * @param slots    占位符名称到替换值的映射
     * @return 替换后的字符串
     */
    public static String fillSlots(String template, Map<String, String> slots) {
        if (template == null) {
            return "";
        }
        if (slots == null || slots.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : slots.entrySet()) {
            String value = StrUtil.emptyIfNull(entry.getValue());
            result = result.replace("{" + entry.getKey() + "}", value);
        }
        return result;
    }

    /**
     * 将包含 {@code --- section: name ---} 分隔符的模板文件解析为 name → content 映射
     */
    public static Map<String, String> parseSections(String content) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (StrUtil.isBlank(content)) {
            return sections;
        }
        Matcher matcher = SECTION_HEADER.matcher(content);
        int lastStart = -1;
        String lastName = null;
        while (matcher.find()) {
            if (lastName != null) {
                sections.put(lastName, trimSection(content.substring(lastStart, matcher.start())));
            }
            lastName = matcher.group(1);
            lastStart = matcher.end();
        }
        if (lastName != null) {
            sections.put(lastName, trimSection(content.substring(lastStart)));
        }
        return sections;
    }

    /**
     * 去掉 section 内容的首尾空行，但保留内部结构
     */
    private static String trimSection(String section) {
        // 去掉开头的一个换行和结尾的空白
        if (section.startsWith("\n")) {
            section = section.substring(1);
        }
        return section.stripTrailing();
    }
}
