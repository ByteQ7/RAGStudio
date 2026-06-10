package com.byteq.ai.ragstudio.ingestion.domain.enums;

import com.byteq.ai.ragstudio.ingestion.node.EnricherNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文本块富化类型枚举
 * <p>
 * 定义对文档分块进行富化处理的类型，用于增强分块的元数据和检索能力。
 * 由 {@link EnricherNode} 使用，
 * 通过调用大语言模型（LLM）对每个文本块执行不同类型的富化任务。
 * </p>
 * <p>
 * 类型值使用小写 snake_case，如 keywords、summary。
 * 支持 JSON 序列化/反序列化。
 * </p>
 *
 * @see EnricherNode
 */
@Getter
@RequiredArgsConstructor
public enum ChunkEnrichType {

    /**
     * 关键词提取
     * 从单个文本块中提取重要的关键词或短语，存储在文本块的 keywords 元数据字段中。
     * 用于提升文本块的检索匹配能力。
     */
    KEYWORDS("keywords"),

    /**
     * 摘要生成
     * 为单个文本块生成简短的摘要描述，存储在文本块的 summary 元数据字段中。
     * 用于快速了解文本块的核心内容。
     */
    SUMMARY("summary"),

    /**
     * 元数据添加
     * 从文本块中提取额外的结构化元数据信息，合并到文本块的元数据中。
     * 用于丰富文本块的上下文信息，提升检索过滤的精确度。
     */
    METADATA("metadata");

    /**
     * 类型值（小写 snake_case）
     * 用于 JSON 序列化和配置解析
     */
    private final String value;

    /**
     * 根据字符串值解析富化类型
     * <p>
     * 支持两种格式的输入：
     * <ul>
     *   <li>小写值：如 "keywords"、"summary"、"metadata"</li>
     *   <li>枚举名称：如 "KEYWORDS"、"SUMMARY"、"METADATA"</li>
     * </ul>
     * </p>
     *
     * @param value 富化类型字符串
     * @return 对应的枚举值，如果输入为 null 则返回 null
     * @throws IllegalArgumentException 如果无法匹配任何已知类型
     */
    @JsonCreator
    public static ChunkEnrichType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (ChunkEnrichType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown chunk enrich type: " + value);
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

    /**
     * 规范化输入字符串
     * 去除首尾空白、转小写、将连字符替换为下划线
     */
    private static String normalize(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }
}
