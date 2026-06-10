package com.byteq.ai.ragstudio.core.parser;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Markdown 文档解析器
 * <p>
 * 专门处理 Markdown 格式的文档解析。与 {@link TikaDocumentParser} 不同，
 * 此解析器保留原始 Markdown 格式文本，不进行 HTML 转换或格式清理，
 * 以保留 Markdown 的结构化信息（标题、列表、代码块等），方便后续的
 * 结构感知分块处理。
 * </p>
 * <p>
 * 支持的 MIME 类型：text/markdown、text/x-markdown、text/plain
 * </p>
 *
 * @see TikaDocumentParser
 * @see DocumentParserSelector
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public String getParserType() {
        return ParserType.MARKDOWN.getType();
    }

    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }

        String text = new String(content, StandardCharsets.UTF_8);
        return ParseResult.ofText(text);
    }

    @Override
    public String extractText(InputStream stream, String fileName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (Exception e) {
            throw new RuntimeException("解析 Markdown 文件失败: " + fileName, e);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && (
                mimeType.equals("text/markdown") ||
                        mimeType.equals("text/x-markdown") ||
                        mimeType.equals("text/plain")
        );
    }
}
