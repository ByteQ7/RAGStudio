package com.byteq.ai.ragstudio.core.parser;

import java.io.InputStream;
import java.util.Map;

/**
 * 文档解析器统一接口
 * <p>
 * 提供文档解析的通用能力，支持多种文档格式（PDF、Word、Markdown、Excel、PPT 等）。
 * 采用策略模式（Strategy Pattern），不同的文档格式由不同的解析器实现处理，
 * 通过 {@link DocumentParserSelector} 统一管理和选择。
 * </p>
 * <p>
 * 系统内置了以下解析器实现：
 * <ul>
 *   <li>{@link TikaDocumentParser} - 基于 Apache Tika 的通用解析器，支持大部分文档格式</li>
 *   <li>{@link MarkdownDocumentParser} - 专门处理 Markdown 格式文档</li>
 * </ul>
 * </p>
 *
 * @see DocumentParserSelector
 * @see ParseResult
 */
public interface DocumentParser {

    /**
     * 获取解析器类型标识
     *
     * @return 解析器类型标识，如 {@link ParserType#TIKA}、{@link ParserType#MARKDOWN}
     */
    String getParserType();

    /**
     * 解析文档内容（从字节数组）
     * <p>
     * 将文档的二进制内容解析为结构化的文本和元数据。
     * 支持通过 options 参数传递额外的解析选项（如编码、语言等）。
     * </p>
     *
     * @param content  文档的二进制字节数组
     * @param mimeType 文档的 MIME 类型（可选），用于帮助解析器选择合适的解析策略
     * @param options  解析选项（可选），解析器特定的配置参数
     * @return 解析结果，包含解析后的文本内容和元数据
     */
    default ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        throw new UnsupportedOperationException("parse(byte[], String, Map) not implemented");
    }

    /**
     * 解析文档内容（从输入流）
     * <p>
     * 从输入流中读取文档内容并提取文本。适用于需要直接处理文件流的场景。
     * </p>
     *
     * @param stream   文档输入流
     * @param fileName 文件名（用于推断文档类型和编码）
     * @return 解析后的纯文本内容
     */
    default String extractText(InputStream stream, String fileName) {
        throw new UnsupportedOperationException("extractText(InputStream, String) not implemented");
    }

    /**
     * 从文档中提取 Markdown 格式的内容（保留表格、标题、列表等结构）
     * <p>
     * 相比 {@link #extractText} 输出的纯文本，Markdown 格式能最大程度保留
     * 文档的表格、标题层级、列表等结构化信息，有利于后续分块和检索。
     * </p>
     *
     * @param stream   文档输入流
     * @param fileName 文件名（用于推断文档类型和编码）
     * @return 解析后的 Markdown 格式内容
     */
    default String extractAsMarkdown(InputStream stream, String fileName) {
        // 默认降级为纯文本提取
        return extractText(stream, fileName);
    }

    /**
     * 检查当前解析器是否支持指定的 MIME 类型
     * <p>
     * 由 {@link DocumentParserSelector#selectByMimeType(String)} 调用，
     * 用于根据文档的 MIME 类型自动匹配合适的解析器。
     * </p>
     *
     * @param mimeType MIME 类型（如 "application/pdf"、"text/markdown"）
     * @return true 如果支持该 MIME 类型，否则返回 false
     */
    default boolean supports(String mimeType) {
        return true;
    }
}
