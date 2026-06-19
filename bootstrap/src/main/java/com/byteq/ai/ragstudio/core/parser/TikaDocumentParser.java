package com.byteq.ai.ragstudio.core.parser;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Apache Tika 通用文档解析器
 * <p>
 * 基于 Apache Tika 库实现的通用文档解析器，支持多种常见文档格式的文本提取。
 * 作为默认的文档解析器，当没有更专门的解析器匹配时使用。
 * </p>
 * <p>
 * 支持的文档格式包括：
 * <ul>
 *   <li>PDF 文档（application/pdf）</li>
 *   <li>Microsoft Word（doc/docx）</li>
 *   <li>Microsoft Excel（xls/xlsx）</li>
 *   <li>Microsoft PowerPoint（ppt/pptx）</li>
 *   <li>HTML/XML 文件</li>
 *   <li>纯文本文件（txt）</li>
 *   <li>OpenDocument 格式（odt/ods/odp）</li>
 *   <li>以及 Tika 支持的其他格式</li>
 * </ul>
 * </p>
 * <p>
 * 注意：Markdown 格式的文档不由此解析器处理，交由 {@link MarkdownDocumentParser} 处理。
 * </p>
 *
 * @see MarkdownDocumentParser
 * @see DocumentParserSelector
 */
@Slf4j
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    /**
     * PDF 解析配置：禁用内联图片提取，减少不必要的内存开销
     */
    private static final PDFParserConfig PDF_CONFIG = new PDFParserConfig();
    static {
        PDF_CONFIG.setExtractInlineImages(false);
        PDF_CONFIG.setExtractUniqueInlineImagesOnly(true);
    }

    @Override
    public String getParserType() {
        return ParserType.TIKA.getType();
    }

    /**
     * 解析文档内容为结构化文本
     * <p>
     * 解析流程:
     * 1. 将字节数组包装为输入流
     * 2. 使用 AutoDetectParser 自动检测文档格式并解析（通过 ParseContext 注入 PDF 配置）
     * 3. 通过 BodyContentHandler 提取正文内容（不限制长度）
     * 4. 调用 TextCleanupUtil 清理文本后返回解析结果
     * </p>
     */
    @Override
    public ParseResult parse(byte[] content, String mimeType, Map<String, Object> options) {
        if (content == null || content.length == 0) {
            return ParseResult.ofText("");
        }

        try (ByteArrayInputStream is = new ByteArrayInputStream(content)) {
            // 使用 AutoDetectParser + ParseContext 使 PDFParserConfig 真正生效
            AutoDetectParser parser = new AutoDetectParser(TikaConfig.getDefaultConfig());
            BodyContentHandler handler = new BodyContentHandler(-1);
            ParseContext parseContext = new ParseContext();
            parseContext.set(PDFParserConfig.class, PDF_CONFIG);
            parser.parse(is, handler, new Metadata(), parseContext);
            String text = handler.toString();
            String cleaned = TextCleanupUtil.cleanup(text);
            return ParseResult.ofText(cleaned);
        } catch (Exception e) {
            log.error("Tika 解析失败，MIME 类型: {}", mimeType, e);
            throw new ServiceException("文档解析失败: " + e.getMessage());
        }
    }

    // 从输入流中提取纯文本内容，使用 Tika 简洁 API 解析后清理文本
    @Override
    public String extractText(InputStream stream, String fileName) {
        try {
            String text = TIKA.parseToString(stream);
            return TextCleanupUtil.cleanup(text);
        } catch (Exception e) {
            log.error("从文件中提取文本内容失败: {}", fileName, e);
            throw new ServiceException("解析文件失败: " + fileName);
        }
    }

    @Override
    public boolean supports(String mimeType) {
        // Tika 支持大部分常见文档格式，但不处理 Markdown 格式
        return mimeType != null && !mimeType.startsWith("text/markdown");
    }
}
