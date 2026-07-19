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
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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

    /**
     * 提取 Markdown 格式内容（保留表格、标题、列表等结构）
     * <p>
     * 使用 Tika 的 ToXMLContentHandler 输出 XHTML，再经 Jsoup 解析后
     * 转换为 Markdown 格式，最大程度保留文档的表格和层级结构。
     * </p>
     */
    @Override
    public String extractAsMarkdown(InputStream stream, String fileName) {
        try {
            // 预读字节到内存，支持 reset() 以便降级时重用
            byte[] bytes = stream.readAllBytes();

            // 首次尝试：Markdown 提取
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
                AutoDetectParser parser = new AutoDetectParser(TikaConfig.getDefaultConfig());
                ToXMLContentHandler handler = new ToXMLContentHandler();
                ParseContext parseContext = new ParseContext();
                parseContext.set(PDFParserConfig.class, PDF_CONFIG);
                parser.parse(bis, handler, new Metadata(), parseContext);
                String xhtml = handler.toString();
                return convertXhtmlToMarkdown(xhtml);
            } catch (Exception e) {
                log.warn("Tika Markdown 提取失败，降级为纯文本: {}", fileName, e);
                // 降级：纯文本提取
                try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
                    return TIKA.parseToString(bis);
                }
            }
        } catch (Exception e) {
            log.error("读取文件流失败: {}", fileName, e);
            throw new ServiceException("解析文件失败: " + fileName);
        }
    }

    /**
     * 将 Tika 输出的 XHTML 转换为 Markdown 格式
     */
    private String convertXhtmlToMarkdown(String xhtml) {
        if (xhtml == null || xhtml.isBlank()) return "";

        Document doc = Jsoup.parse(xhtml);
        StringBuilder md = new StringBuilder();

        // 遍历 body 的子元素
        Element body = doc.body();
        if (body == null) return TextCleanupUtil.cleanup(doc.text());

        for (Element el : body.children()) {
            convertElement(el, md, 0);
        }

        return TextCleanupUtil.cleanup(md.toString().strip());
    }

    // 递归转换 XHTML 元素为 Markdown
    private void convertElement(Element el, StringBuilder md, int depth) {
        String tag = el.tagName().toLowerCase();

        switch (tag) {
            case "h1" -> md.append("# ").append(el.text()).append("\n\n");
            case "h2" -> md.append("## ").append(el.text()).append("\n\n");
            case "h3" -> md.append("### ").append(el.text()).append("\n\n");
            case "h4" -> md.append("#### ").append(el.text()).append("\n\n");
            case "h5" -> md.append("##### ").append(el.text()).append("\n\n");
            case "h6" -> md.append("###### ").append(el.text()).append("\n\n");
            case "p" -> {
                md.append(convertInline(el)).append("\n\n");
            }
            case "ul", "ol" -> {
                boolean ordered = tag.equals("ol");
                int index = 1;
                for (Element li : el.children()) {
                    if (li.tagName().equals("li")) {
                        String prefix = ordered ? (index++) + ". " : "- ";
                        md.append("  ".repeat(depth)).append(prefix).append(convertInline(li)).append("\n");
                        // 处理嵌套列表
                        for (Element child : li.children()) {
                            if (child.tagName().equals("ul") || child.tagName().equals("ol")) {
                                convertElement(child, md, depth + 1);
                            }
                        }
                    }
                }
                md.append("\n");
            }
            case "table" -> {
                convertTable(el, md);
                md.append("\n");
            }
            case "pre" -> {
                md.append("```\n").append(el.text()).append("\n```\n\n");
            }
            case "blockquote" -> {
                for (Element child : el.children()) {
                    String text = convertInline(child);
                    if (!text.isBlank()) {
                        md.append("> ").append(text).append("\n");
                    }
                }
                md.append("\n");
            }
            case "hr" -> md.append("---\n\n");
            case "div" -> {
                for (Element child : el.children()) {
                    convertElement(child, md, depth);
                }
            }
            default -> {
                // 其他标签当作段落处理
                String text = convertInline(el);
                if (!text.isBlank()) {
                    md.append(text).append("\n\n");
                }
            }
        }
    }

    // 转换内联元素为 Markdown 文本
    private String convertInline(Element el) {
        StringBuilder sb = new StringBuilder();
        for (org.jsoup.nodes.Node node : el.childNodes()) {
            if (node instanceof org.jsoup.nodes.TextNode tn) {
                sb.append(tn.text());
            } else if (node instanceof Element child) {
                String tag = child.tagName().toLowerCase();
                switch (tag) {
                    case "strong", "b" -> sb.append("**").append(child.text()).append("**");
                    case "em", "i" -> sb.append("*").append(child.text()).append("*");
                    case "code" -> sb.append("`").append(child.text()).append("`");
                    case "a" -> {
                        String href = child.attr("href");
                        if (!href.isBlank()) {
                            sb.append("[").append(child.text()).append("](").append(href).append(")");
                        } else {
                            sb.append(child.text());
                        }
                    }
                    case "br" -> sb.append("\n");
                    case "img" -> {
                        String src = child.attr("src");
                        String alt = child.attr("alt");
                        if (!src.isBlank()) {
                            sb.append("![").append(alt).append("](").append(src).append(")");
                        }
                    }
                    case "sub" -> sb.append("<sub>").append(child.text()).append("</sub>");
                    case "sup" -> sb.append("<sup>").append(child.text()).append("</sup>");
                    default -> sb.append(child.text());
                }
            }
        }
        // 如果元素本身还有自己的文本（直接文本节点）
        if (el.childNodes().isEmpty()) {
            sb.append(el.text());
        }
        String result = sb.toString().replaceAll("\\s+", " ").trim();
        // 转义 Markdown 特殊字符
        return result;
    }

    // 转换表格为 Markdown 表格格式
    private void convertTable(Element table, StringBuilder md) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) return;

        int colCount = 0;
        // 计算最大列数
        for (Element row : rows) {
            int cols = row.select("th, td").size();
            if (cols > colCount) colCount = cols;
        }
        if (colCount == 0) return;

        boolean headerDone = false;
        for (int i = 0; i < rows.size(); i++) {
            Element row = rows.get(i);
            Elements cells = row.select("th, td");
            if (cells.isEmpty()) continue;

            // 写入表头分隔行
            if (!headerDone && row.select("th").size() > 0) {
                appendTableRow(md, cells, colCount);
                // 分隔线
                md.append("|");
                for (int c = 0; c < colCount; c++) {
                    md.append(" --- |");
                }
                md.append("\n");
                headerDone = true;
            } else if (!headerDone) {
                // 无 thead，第一行作为表头
                appendTableRow(md, cells, colCount);
                md.append("|");
                for (int c = 0; c < colCount; c++) {
                    md.append(" --- |");
                }
                md.append("\n");
                headerDone = true;
            } else {
                appendTableRow(md, cells, colCount);
            }
        }
    }

    private void appendTableRow(StringBuilder md, Elements cells, int colCount) {
        md.append("|");
        for (int c = 0; c < colCount; c++) {
            if (c < cells.size()) {
                String text = cells.get(c).text().replace("|", "\\|");
                md.append(" ").append(text).append(" |");
            } else {
                md.append(" |");
            }
        }
        md.append("\n");
    }

    @Override
    public boolean supports(String mimeType) {
        // Tika 支持大部分常见文档格式，但不处理 Markdown 格式
        return mimeType != null && !mimeType.startsWith("text/markdown");
    }
}
