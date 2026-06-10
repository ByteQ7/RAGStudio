package com.byteq.ai.ragstudio.ingestion.domain.enums;

import com.byteq.ai.ragstudio.ingestion.node.EnhancerNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档增强类型枚举
 * <p>
 * 定义对整个文档内容进行增强处理的类型，用于提升文档的检索和理解质量。
 * 由 {@link EnhancerNode} 使用，
 * 通过调用大语言模型（LLM）执行不同类型的增强任务。
 * </p>
 * <p>
 * 类型值使用小写 snake_case，如 context_enhance、keywords。
 * 支持 JSON 序列化/反序列化。
 * </p>
 *
 * @see EnhancerNode
 */
@Getter
@RequiredArgsConstructor
public enum EnhanceType {

    /**
     * 上下文增强
     * 对原始文本进行重写或扩写，补充上下文信息，提升文本的语义理解和检索质量。
     * 使用原始文本（rawText）作为输入，输出增强后的文本（enhancedText）。
     */
    CONTEXT_ENHANCE("context_enhance"),

    /**
     * 关键词提取
     * 从文档中提取重要的关键词或短语，用于提升检索的准确率和召回率。
     * 提取的关键词存储在上下文的 keywords 字段中。
     */
    KEYWORDS("keywords"),

    /**
     * 问题生成
     * 基于文档内容生成可能被用户问到的问题，用于构建 QA 检索对。
     * 生成的问题存储在上下文的 questions 字段中。
     */
    QUESTIONS("questions"),

    /**
     * 元数据提取
     * 提取文档的结构化元数据信息（如作者、创建日期、分类、标签等），
     * 用于后续的过滤检索和结果展示。
     */
    METADATA("metadata");

    /**
     * 类型值（小写 snake_case）
     * 用于 JSON 序列化和配置解析
     */
    private final String value;

    /**
     * 根据字符串值解析增强类型
     * <p>
     * 支持两种格式的输入：
     * <ul>
     *   <li>小写值：如 "context_enhance"、"keywords"、"questions"、"metadata"</li>
     *   <li>枚举名称：如 "CONTEXT_ENHANCE"、"KEYWORDS"、"QUESTIONS"、"METADATA"</li>
     * </ul>
     * </p>
     *
     * @param value 增强类型字符串
     * @return 对应的枚举值，如果输入为 null 则返回 null
     * @throws IllegalArgumentException 如果无法匹配任何已知类型
     */
    @JsonCreator
    public static EnhanceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (EnhanceType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown enhance type: " + value);
    }

    /**
     * 规范化输入字符串
     * 去除首尾空白、转小写、将连字符替换为下划线
     */
    private static String normalize(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 获取 JSON 序列化值
     *
     * @return 小写 snake_case 格式的类型值
     */
    @JsonValue
    public String getValue() {
        return value;
    }
}
