package com.byteq.ai.ragstudio.core.parser;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.knowledge.service.DocumentVisionExtractor;
import lombok.RequiredArgsConstructor;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
@RequiredArgsConstructor
public class TikaDocumentParser implements DocumentParser {

    private static final Tika TIKA = new Tika();

    private static final int MAX_VISION_PAGES = 10;

    /**
     * PDF 表格提取器（Tabula 引擎），用于补充 Tika 在 PDF 表格检测上的不足。
     */
    private final PdfTableExtractor pdfTableExtractor;

    /**
     * 文档视觉提取器，用于提取包含图片/图表的页面内容
     */
    private final DocumentVisionExtractor documentVisionExtractor;

    /**
     * PDF 解析配置：禁用内联图片提取，减少不必要的内存开销
     */
    private static final PDFParserConfig PDF_CONFIG = new PDFParserConfig();
    static {
        PDF_CONFIG.setExtractInlineImages(false);
        PDF_CONFIG.setExtractUniqueInlineImagesOnly(true);
        // 开启文本位置排序，帮助 Tika 更好地从文字坐标推断表格结构
        PDF_CONFIG.setSortByPosition(true);
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
            AutoDetectParser parser = new AutoDetectParser(TikaConfig.getDefaultConfig());
            BodyContentHandler handler = new BodyContentHandler(-1);
            ParseContext parseContext = new ParseContext();
            parseContext.set(PDFParserConfig.class, PDF_CONFIG);

            // 使用 Future 包裹解析，设置 60 秒超时，防止损坏/复杂文档卡死线程
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Void> future = executor.submit(() -> {
                    parser.parse(is, handler, new Metadata(), parseContext);
                    return null;
                });
                future.get(60, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Tika 解析超时（60s），MIME 类型: {}，文件大小: {} bytes", mimeType, content.length);
                throw new ServiceException("文档解析超时（超过 60 秒），请确认文件未损坏或尝试更小的文件");
            } catch (Exception e) {
                log.error("Tika 解析失败，MIME 类型: {}", mimeType, e);
                throw new ServiceException("文档解析失败: " + e.getMessage());
            } finally {
                executor.shutdownNow();
            }

            String text = handler.toString();
            String cleaned = TextCleanupUtil.cleanup(text);
            return ParseResult.ofText(cleaned);
        } catch (ServiceException e) {
            throw e;
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
            byte[] bytes = stream.readAllBytes();

            if (isPdfFile(fileName, bytes)) {
                return extractPdfWithVision(bytes, fileName);
            }

            if (isImageFile(fileName)) {
                String mimeType = detectImageMime(bytes, fileName);
                String visionText = documentVisionExtractor.extractImageWithVision(bytes, mimeType);
                if (!visionText.isBlank()) {
                    return visionText;
                }
                log.warn("图片视觉提取失败，降级为 Tika 解析: {}", fileName);
            }

            String markdown = tikaExtractMarkdown(bytes, fileName);

            if (isZipDocument(fileName)) {
                markdown = visionEnhanceZipDocument(markdown, bytes, fileName);
            }

            return markdown;
        } catch (Exception e) {
            log.error("读取文件流失败: {}", fileName, e);
            throw new ServiceException("解析文件失败: " + fileName);
        }
    }

    /**
     * 纯 Tika Markdown 提取（仅用于非 PDF 文档）
     */
    private String tikaExtractMarkdown(byte[] bytes, String fileName) {
        String markdown;
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            AutoDetectParser parser = new AutoDetectParser(TikaConfig.getDefaultConfig());
            ToXMLContentHandler handler = new ToXMLContentHandler();
            ParseContext parseContext = new ParseContext();
            parseContext.set(PDFParserConfig.class, PDF_CONFIG);
            parser.parse(bis, handler, new Metadata(), parseContext);
            String xhtml = handler.toString();
            markdown = convertXhtmlToMarkdown(xhtml);
        } catch (Throwable e) {
            log.warn("Tika Markdown 提取失败，降级为纯文本: {}", fileName, e);
            try {
                markdown = TIKA.parseToString(new ByteArrayInputStream(bytes));
            } catch (Throwable innerEx) {
                log.warn("Tika 纯文本提取也失败: {}", fileName, innerEx);
                markdown = "";
            }
        }
        return markdown;
    }

    /**
     * PDF 提取策略：
     * <ol>
     *   <li>检测 PDF 是否包含表格或图片</li>
     *   <li>如果有表格或图片 → 使用多模态大模型（Qwen3.5-9B）提取</li>
     *   <li>如果纯文本型（无表格/图片） → 使用 Tika + Tabula 提取</li>
     *   <li>Tika 提取为空时降级为 PDFBox 逐页提取</li>
     * </ol>
     */
    private String extractPdfWithVision(byte[] bytes, String fileName) {
        List<Integer> imagePages = documentVisionExtractor.findPagesWithImages(bytes);
        boolean hasImages = !imagePages.isEmpty();
        boolean hasTables = pdfTableExtractor.hasTables(bytes);

        if (hasImages || hasTables) {
            log.info("PDF 包含表格或图片，使用多模态大模型提取: hasTables={}, hasImages={}", hasTables, hasImages);
            String visionText = documentVisionExtractor.extractPdfWithVision(bytes);
            if (!visionText.isBlank()) {
                return visionText;
            }
            log.warn("多模态提取失败，降级为 Tika + Tabula 提取");
        }

        // 纯文本型 PDF：使用 Tika + Tabula
        String tikaText = tikaExtractMarkdown(bytes, fileName);
        if (!tikaText.isBlank()) {
            return appendTabulaTables(tikaText, bytes);
        }

        log.info("Tika 提取为空，切换为 PDFBox 逐页提取");

        try (PDDocument document = Loader.loadPDF(bytes)) {
            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.min(totalPages, MAX_VISION_PAGES);
            StringBuilder md = new StringBuilder();

            for (int i = 0; i < pagesToProcess; i++) {
                if (i > 0) {
                    md.append("---\n\n");
                }
                md.append("## 第 ").append(i + 1).append(" 页\n\n");
                String pageContent;
                if (imagePages.contains(i)) {
                    pageContent = documentVisionExtractor.extractPageTextWithVision(bytes, i);
                    if (pageContent.isBlank()) {
                        pageContent = extractPdfPageText(document, i);
                    }
                } else {
                    pageContent = extractPdfPageText(document, i);
                }
                if (!pageContent.isBlank()) {
                    md.append(pageContent.strip()).append("\n\n");
                }
            }

            if (totalPages > pagesToProcess) {
                log.warn("PDF 页数超过限制，仅处理前 {} 页（共 {} 页）", pagesToProcess, totalPages);
            }

            return appendTabulaTables(md.toString().strip(), bytes);
        } catch (Exception e) {
            log.error("PDF 逐页提取失败", e);
            return tikaText;
        }
    }

    /**
     * 用 Tabula 提取 PDF 表格，追加到文本末尾
     */
    private String appendTabulaTables(String text, byte[] bytes) {
        List<PdfTableExtractor.ExtractedTable> tables = pdfTableExtractor.extractTables(bytes);
        if (tables.isEmpty()) {
            return text;
        }
        log.info("Tabula 提取到 {} 张表格", tables.size());
        StringBuilder result = new StringBuilder(text);
        result.append("\n\n---\n\n## 表格提取\n\n");
        for (PdfTableExtractor.ExtractedTable table : tables) {
            result.append(table.markdown()).append("\n\n");
        }
        return result.toString().strip();
    }

    /**
     * 使用 PDFBox 提取指定页面的纯文本（复用已打开的 PDDocument）
     */
    private static String extractPdfPageText(PDDocument document, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return TextCleanupUtil.cleanup(stripper.getText(document));
        } catch (Exception e) {
            log.warn("PDFBox 页面文本提取失败: page={}", pageIndex, e);
            return "";
        }
    }

    /**
     * 对 ZIP 文档（DOCX/PPTX/ODT）补充视觉提取嵌入图片中的文字
     */
    private String visionEnhanceZipDocument(String markdown, byte[] bytes, String fileName) {
        String fileType = resolveFileType(fileName);
        String visionText = documentVisionExtractor.extractImagesFromZipWithVision(bytes, fileType);
        if (!visionText.isBlank()) {
            return markdown + "\n\n---\n\n## 文档图片提取\n\n" + visionText;
        }
        return markdown;
    }

    /**
     * 判断是否为 ZIP-based 文档格式（DOCX/PPTX/ODT 等）
     */
    private static boolean isZipDocument(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".pptx")
                || lower.endsWith(".odt") || lower.endsWith(".ods") || lower.endsWith(".odp");
    }

    /**
     * 判断是否为图片文件
     */
    private static boolean isImageFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    /**
     * 根据字节魔数和扩展名检测图片 MIME 类型
     */
    private static String detectImageMime(byte[] bytes, String fileName) {
        if (bytes != null && bytes.length >= 4) {
            if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) return "image/jpeg";
            if (bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50) return "image/png";
            if (bytes[0] == (byte) 0x47 && bytes[1] == (byte) 0x49) return "image/gif";
            if (bytes[0] == (byte) 0x42 && bytes[1] == (byte) 0x4D) return "image/bmp";
            if (bytes[0] == (byte) 0x52 && bytes[1] == (byte) 0x49) return "image/webp";
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".bmp")) return "image/bmp";
        }
        return "image/png";
    }

    /**
     * 根据文件名解析文件类型标识
     */
    private static String resolveFileType(String fileName) {
        if (fileName == null) return "";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".xlsx")) return "xlsx";
        if (lower.endsWith(".pptx")) return "pptx";
        if (lower.endsWith(".odt")) return "odt";
        if (lower.endsWith(".ods")) return "ods";
        if (lower.endsWith(".odp")) return "odp";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp"))
            return lower.substring(lower.lastIndexOf('.') + 1);
        return "";
    }

    /**
     * 判断字节流是否为 PDF 文件
     * <p>
     * 优先通过文件名后缀判断；文件名不可用时通过 PDF 魔数（%PDF）检测。
     * </p>
     */
    private static boolean isPdfFile(String fileName, byte[] bytes) {
        if (fileName != null && fileName.toLowerCase().endsWith(".pdf")) {
            return true;
        }
        // PDF 魔数：%PDF（0x25 0x50 0x44 0x46）
        if (bytes != null && bytes.length >= 4) {
            return bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46;
        }
        return false;
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
                String text = convertInline(cells.get(c)).replace("|", "\\|");
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
