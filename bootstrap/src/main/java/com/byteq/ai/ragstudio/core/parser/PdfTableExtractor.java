package com.byteq.ai.ragstudio.core.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.BasicExtractionAlgorithm;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF 表格提取器
 * <p>
 * 基于 Tabula 引擎从 PDF 中提取表格，专门补充 Tika 在 PDF 表格检测上的不足。
 * Tabula 通过分析文字在页面上的坐标位置来检测表格区域，比 Tika 的启发式方法更准确，
 * 特别是对有边框表格（SpreadsheetExtractionAlgorithm）和无边框表格（BasicExtractionAlgorithm）都有效。
 * </p>
 *
 * <p>
 * 使用策略：
 * <ol>
 *   <li>优先使用 SpreadsheetExtractionAlgorithm（适合有边框的规则表格）</li>
 *   <li>未检测到时降级为 BasicExtractionAlgorithm（适合无边框表格）</li>
 * </ol>
 * </p>
 *
 * <p>
 * 与 {@link DocumentVisionExtractor} 不同，本提取器不依赖多模态 LLM，
 * 纯算法提取，速度快、成本低，但只能处理文本型 PDF（非扫描件）。
 * </p>
 *
 * @see TikaDocumentParser
 * @see DocumentVisionExtractor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfTableExtractor {

    /**
     * Tabula 最大处理页数（与视觉提取保持一致）
     */
    private static final int MAX_PDF_PAGES = 10;

    /**
     * 从 PDF 字节数组中提取所有表格及位置信息，用于与 Tika 文本合并
     * <p>
     * 返回的 {@link ExtractedTable} 包含 Markdown 格式表格、页码、和用于
     * 原文定位的搜索关键字（首行单元格内容拼接），便于 {@link TikaDocumentParser}
     * 将表格替换到原文中的正确位置。
     * </p>
     *
     * @param pdfBytes PDF 文件的完整字节数组
     * @return 表格信息列表，无可提取表格或出错时返回空列表
     */
    public List<ExtractedTable> extractTables(byte[] pdfBytes) {
        List<ExtractedTable> tables = new ArrayList<>();

        if (pdfBytes == null || pdfBytes.length == 0) {
            return tables;
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
            BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm();

            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.min(totalPages, MAX_PDF_PAGES);

            try (ObjectExtractor extractor = new ObjectExtractor(document)) {
                // Tabula page 索引从 1 开始
                for (int pageNum = 1; pageNum <= pagesToProcess; pageNum++) {
                    Page page = extractor.extract(pageNum);

                    // 策略 1：Spreadsheet 算法 — 适合有边框的规则表格
                    List<Table> rawTables = sea.extract(page);
                    if (rawTables.isEmpty()) {
                        // 策略 2：Basic 算法 — 适合无边框表格
                        rawTables = bea.extract(page);
                    }

                    for (Table rawTable : rawTables) {
                        ExtractedTable et = convertToExtractedTable(rawTable, pageNum);
                        if (et != null) {
                            tables.add(et);
                            log.debug("Tabula 提取到表格: page={}, rows={}, cols={}",
                                    pageNum,
                                    rawTable.getRows().size(),
                                    rawTable.getRows().isEmpty() ? 0 : rawTable.getRows().get(0).size());
                        }
                    }
                }
            }

            if (totalPages > MAX_PDF_PAGES) {
                log.warn("PDF 页数超过限制，Tabula 仅处理前 {} 页（共 {} 页）",
                        MAX_PDF_PAGES, totalPages);
            }

            log.info("Tabula 从 PDF 中提取了 {} 张表格（共 {} 页）", tables.size(), pagesToProcess);

        } catch (Exception e) {
            log.warn("Tabula 表格提取失败，跳过表格提取", e);
        }

        return tables;
    }

    /**
     * 将 Tabula 的 Table 对象转换为带位置信息的 ExtractedTable
     */
    private ExtractedTable convertToExtractedTable(Table table, int pageNumber) {
        List<List<RectangularTextContainer>> rows = table.getRows();
        if (rows == null || rows.isEmpty()) return null;

        int colCount = rows.stream()
                .mapToInt(List::size)
                .max()
                .orElse(0);
        if (colCount == 0) return null;

        StringBuilder md = new StringBuilder();

        // 表头行（第一行）
        appendRow(md, rows.get(0), colCount);

        // 分隔线
        md.append("|");
        for (int c = 0; c < colCount; c++) {
            md.append(" --- |");
        }
        md.append("\n");

        // 数据行
        for (int r = 1; r < rows.size(); r++) {
            appendRow(md, rows.get(r), colCount);
        }

        // 构建搜索关键字：取首行所有单元格文本拼接，作为唯一标识
        String searchKey = rows.get(0).stream()
                .map(RectangularTextContainer::getText)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "));
        if (searchKey.isBlank()) {
            // 首行无文本时尝试第二行
            if (rows.size() > 1) {
                searchKey = rows.get(1).stream()
                        .map(RectangularTextContainer::getText)
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.joining(" "));
            }
        }
        // 截断过长的 key，保留前 120 字符
        if (searchKey.length() > 120) {
            searchKey = searchKey.substring(0, 120);
        }

        return new ExtractedTable(md.toString(), pageNumber, searchKey);
    }

    /**
     * 追加一行 Markdown 表格行
     */
    private void appendRow(StringBuilder md, List<RectangularTextContainer> cells, int colCount) {
        md.append("|");
        for (int c = 0; c < colCount; c++) {
            String text = "";
            if (c < cells.size()) {
                text = cells.get(c).getText();
            }
            text = text.replace("|", "\\|");
            text = text.replace("\n", " ").replace("\r", " ").trim();
            md.append(" ").append(text).append(" |");
        }
        md.append("\n");
    }

    /**
     * 快速检测 PDF 是否包含表格
     * <p>
     * 扫描前 5 页，同时使用 Spreadsheet 和 Basic 算法检测。
     * 用于决策是否需要切换为多模态大模型提取。
     * </p>
     */
    public boolean hasTables(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) return false;
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
            BasicExtractionAlgorithm bea = new BasicExtractionAlgorithm();
            int totalPages = document.getNumberOfPages();
            int pagesToCheck = Math.min(totalPages, 5);
            try (ObjectExtractor extractor = new ObjectExtractor(document)) {
                for (int pageNum = 1; pageNum <= pagesToCheck; pageNum++) {
                    Page page = extractor.extract(pageNum);
                    if (!sea.extract(page).isEmpty() || !bea.extract(page).isEmpty()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Tabula 表格检测失败", e);
        }
        return false;
    }

    /**
     * 提取的表格信息
     *
     * @param markdown   GFM Markdown 格式的表格字符串
     * @param pageNumber 表格所在的 PDF 页码（1-based）
     * @param searchKey  用于在 Tika 文本中定位此表格的搜索关键字
     */
    public record ExtractedTable(String markdown, int pageNumber, String searchKey) {
    }
}
