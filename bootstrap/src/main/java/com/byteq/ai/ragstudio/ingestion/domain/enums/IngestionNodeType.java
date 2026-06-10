package com.byteq.ai.ragstudio.ingestion.domain.enums;

import com.byteq.ai.ragstudio.ingestion.node.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 摄入节点类型枚举
 * <p>
 * 定义文档摄入流水线中支持的节点类型。
 * 每个节点类型对应一个具体的 {@link IngestionNode} 实现，
 * 负责流水线中的一个特定处理步骤。
 * </p>
 * <p>
 * 典型的流水线执行顺序：FETCHER → PARSER → CHUNKER → ENRICHER → ENHANCER → INDEXER
 * </p>
 * <p>
 * 类型值使用小写 snake_case，如 fetcher、parser、chunker，用于配置解析和日志记录。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum IngestionNodeType {

    /**
     * 文档获取节点
     * 从各种数据源（本地文件、HTTP、S3、飞书等）获取文档的原始字节流
     * 对应实现：{@link FetcherNode}
     */
    FETCHER("fetcher"),

    /**
     * 文档解析节点
     * 将获取到的原始字节流解析为结构化文本内容
     * 对应实现：{@link ParserNode}
     */
    PARSER("parser"),

    /**
     * 文档增强节点
     * 对整个文档级别的文本进行 AI 驱动的增强处理（如上下文增强、关键词提取）
     * 对应实现：{@link EnhancerNode}
     */
    ENHANCER("enhancer"),

    /**
     * 文本分块节点
     * 将完整文本按策略切分为多个较小的文本块（Chunk）
     * 对应实现：{@link ChunkerNode}
     */
    CHUNKER("chunker"),

    /**
     * 分块富化节点
     * 对每个文本块进行 AI 驱动的元数据富化处理
     * 对应实现：{@link EnricherNode}
     */
    ENRICHER("enricher"),

    /**
     * 向量索引节点
     * 将文本块及其向量嵌入写入向量数据库，用于后续的相似度检索
     * 对应实现：{@link IndexerNode}
     */
    INDEXER("indexer");

    /**
     * 节点类型的字符串值（小写 snake_case）
     * 用于配置解析、日志记录和节点路由
     */
    private final String value;

    /**
     * 根据字符串值获取节点类型枚举
     * <p>
     * 支持两种格式的输入：
     * <ul>
     *   <li>小写值：如 "fetcher"、"parser"、"chunker"</li>
     *   <li>枚举名称：如 "FETCHER"、"PARSER"、"CHUNKER"</li>
     * </ul>
     * </p>
     *
     * @param value 节点类型字符串
     * @return 对应的枚举值，如果输入为 null 则返回 null
     * @throws IllegalArgumentException 如果无法匹配任何已知类型
     */
    public static IngestionNodeType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (IngestionNodeType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown node type: " + value);
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
