package com.byteq.ai.ragstudio.ingestion.domain.enums;

import com.byteq.ai.ragstudio.ingestion.strategy.fetcher.*;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档源类型枚举
 * <p>
 * 定义支持的文档来源类型，用于标识文档的获取方式。
 * 不同的源类型对应不同的 {@link DocumentFetcher} 实现。
 * </p>
 * <p>
 * 类型值使用小写 snake_case，如 file、url、feishu、s3。
 * 支持 JSON 序列化/反序列化，可通过字符串值或枚举名称两种方式解析。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum SourceType {

    /**
     * 本地文件系统
     * 文档来源为服务器本地文件系统上的文件，通过 {@link LocalFileFetcher} 获取
     */
    FILE("file"),

    /**
     * 网络 URL
     * 文档来源为 HTTP/HTTPS 网络链接，通过 {@link HttpUrlFetcher} 获取
     */
    URL("url"),

    /**
     * 飞书文档
     * 文档来源为飞书（Feishu/Lark）在线文档，通过 {@link FeishuFetcher} 获取
     */
    FEISHU("feishu"),

    /**
     * S3 对象存储
     * 文档来源为 S3 兼容的对象存储（如 RustFS、MinIO），通过 {@link S3Fetcher} 获取
     */
    S3("s3");

    /**
     * 类型值（小写 snake_case）
     * 用于 JSON 序列化和配置解析
     */
    private final String value;

    /**
     * 根据字符串值解析源类型
     * <p>
     * 支持两种格式的输入：
     * <ul>
     *   <li>小写值：如 "file"、"url"、"feishu"、"s3"</li>
     *   <li>枚举名称：如 "FILE"、"URL"、"FEISHU"、"S3"</li>
     * </ul>
     * </p>
     *
     * @param value 源类型字符串
     * @return 对应的枚举值，如果输入为 null 则返回 null
     * @throws IllegalArgumentException 如果无法匹配任何已知类型
     */
    @JsonCreator
    public static SourceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalize(value);
        for (SourceType type : values()) {
            if (type.value.equalsIgnoreCase(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown source type: " + value);
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
