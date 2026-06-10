package com.byteq.ai.ragstudio.knowledge.enums;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 文档来源类型枚举
 * <p>
 * 定义文档的来源方式，支持本地文件上传和远程 URL 获取两种模式。
 * 不同的来源类型会影响文档的上传处理流程和定时同步策略。
 */
@Getter
@RequiredArgsConstructor
public enum SourceType {

    /**
     * 本地文件上传：通过 Multipart 文件上传接口将本地文件上传至系统
     */
    FILE("file"),

    /**
     * 远程 URL 获取：从远程 HTTP/HTTPS 地址获取文档内容，支持定时同步
     */
    URL("url");

    /**
     * 来源类型值（对应数据库中的存储值）
     */
    private final String value;

    /**
     * 根据值获取枚举
     * <p>支持多种别名字段，如 "localfile" 和 "local_file" 均可匹配 FILE 类型。</p>
     *
     * @param value 来源类型值
     * @return 对应的枚举，如果未找到返回 null
     */
    public static SourceType fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        // 兼容多种文件类型的别名
        if ("file".equals(normalized) || "localfile".equals(normalized) || "local_file".equals(normalized)) {
            return FILE;
        }
        if ("url".equals(normalized)) {
            return URL;
        }
        return null;
    }

    /**
     * 解析来源类型，空值或非法值抛出异常
     *
     * @param value 来源类型值
     * @return 对应的枚举
     * @throws IllegalArgumentException 如果值为空或不支持的类型
     */
    public static SourceType normalize(String value) {
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("来源类型不能为空");
        }
        SourceType result = fromValue(value);
        if (result == null) {
            throw new IllegalArgumentException("不支持的来源类型: " + value);
        }
        return result;
    }
}
