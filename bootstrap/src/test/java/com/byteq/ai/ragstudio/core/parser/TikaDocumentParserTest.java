package com.byteq.ai.ragstudio.core.parser;

import com.byteq.ai.ragstudio.knowledge.service.DocumentVisionExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TikaDocumentParserTest {

    @Mock
    private PdfTableExtractor pdfTableExtractor;

    @Mock
    private DocumentVisionExtractor documentVisionExtractor;

    private TikaDocumentParser parser;

    @BeforeEach
    void setUp() {
        parser = new TikaDocumentParser(pdfTableExtractor, documentVisionExtractor);
    }

    // ========== 规则 1：纯文本 PDF → Tika + Tabula ==========

    @Test
    void plainTextPdf_shouldUseTikaAndTabula() throws Exception {
        byte[] pdfBytes = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
                + "xref\n0 4\n...\n%%EOF").getBytes();
        when(documentVisionExtractor.findPagesWithImages(any())).thenReturn(List.of());
        when(pdfTableExtractor.hasTables(any())).thenReturn(false);
        when(pdfTableExtractor.extractTables(any())).thenReturn(List.of());

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(pdfBytes), "test.pdf");

        assertNotNull(result);
        verify(documentVisionExtractor).findPagesWithImages(any());
        verify(pdfTableExtractor).hasTables(any());
        verify(pdfTableExtractor).extractTables(any());
        verify(documentVisionExtractor, never()).extractPdfWithVision(any());
    }

    // ========== 规则 2：含表格/图片的 PDF → 多模态大模型 ==========

    @Test
    void pdfWithTables_shouldUseVision() throws Exception {
        byte[] pdfBytes = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
                + "xref\n0 4\n...\n%%EOF").getBytes();
        when(documentVisionExtractor.findPagesWithImages(any())).thenReturn(List.of());
        when(pdfTableExtractor.hasTables(any())).thenReturn(true);
        when(documentVisionExtractor.extractPdfWithVision(any()))
                .thenReturn("# 表格标题\n\n| 列1 | 列2 |\n| --- | --- |\n| 数据1 | 数据2 |");

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(pdfBytes), "test.pdf");

        assertNotNull(result);
        assertTrue(result.contains("表格标题"));
        assertTrue(result.contains("| 列1 | 列2 |"));
        verify(documentVisionExtractor).extractPdfWithVision(any());
        verify(pdfTableExtractor, never()).extractTables(any());
    }

    @Test
    void pdfWithImages_shouldUseVision() throws Exception {
        byte[] pdfBytes = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
                + "xref\n0 4\n...\n%%EOF").getBytes();
        when(documentVisionExtractor.findPagesWithImages(any())).thenReturn(List.of(0));
        when(pdfTableExtractor.hasTables(any())).thenReturn(false);
        when(documentVisionExtractor.extractPdfWithVision(any()))
                .thenReturn("# 图片页内容\n\n图表描述文字");

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(pdfBytes), "test.pdf");

        assertNotNull(result);
        assertTrue(result.contains("图片页内容"));
        verify(documentVisionExtractor).extractPdfWithVision(any());
    }

    @Test
    void pdfWithTables_visionFails_shouldFallbackToTikaAndTabula() throws Exception {
        byte[] pdfBytes = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
                + "xref\n0 4\n...\n%%EOF").getBytes();
        when(documentVisionExtractor.findPagesWithImages(any())).thenReturn(List.of());
        when(pdfTableExtractor.hasTables(any())).thenReturn(true);
        when(documentVisionExtractor.extractPdfWithVision(any())).thenReturn("");
        when(pdfTableExtractor.extractTables(any())).thenReturn(List.of());

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(pdfBytes), "test.pdf");

        assertNotNull(result);
        verify(documentVisionExtractor).extractPdfWithVision(any());
        verify(pdfTableExtractor).extractTables(any());
    }

    // ========== 规则 3：其他文档 ==========

    @Test
    void imageFile_shouldUseVision() throws Exception {
        byte[] pngBytes = createMinimalPng();
        when(documentVisionExtractor.extractImageWithVision(any(), anyString()))
                .thenReturn("# 图片文字\n\n提取的内容");

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(pngBytes), "test.png");

        assertNotNull(result);
        assertTrue(result.contains("图片文字"));
        verify(documentVisionExtractor).extractImageWithVision(any(), anyString());
    }

    @Test
    void imageFile_visionFails_shouldFallbackToTika() throws Exception {
        byte[] pngBytes = createMinimalPng();
        when(documentVisionExtractor.extractImageWithVision(any(), anyString()))
                .thenReturn("");

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(pngBytes), "test.png");

        assertNotNull(result);
        verify(documentVisionExtractor).extractImageWithVision(any(), anyString());
    }

    @Test
    void wordDocument_shouldUseTikaAndVisionForEmbeddedImages() throws Exception {
        byte[] docxBytes = "PK\u0003\u0004PK\u0003\u0004PK\u0003\u0004dummy zip content".getBytes();
        when(documentVisionExtractor.extractImagesFromZipWithVision(any(), anyString()))
                .thenReturn("# 嵌入图片文字\n\n表格内容");

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(docxBytes), "test.docx");

        assertNotNull(result);
        verify(documentVisionExtractor).extractImagesFromZipWithVision(any(), anyString());
    }

    @Test
    void plainTextFile_shouldUseTikaOnly() throws Exception {
        byte[] txtBytes = "Hello World\n这是纯文本内容。".getBytes();

        String result = parser.extractAsMarkdown(new ByteArrayInputStream(txtBytes), "test.txt");

        assertNotNull(result);
        assertFalse(result.isBlank());
        verifyNoInteractions(documentVisionExtractor);
    }

    // ========== 边界情况 ==========

    @Test
    void invalidPdf_shouldReturnEmpty() throws Exception {
        byte[] invalidPdf = "Not a PDF content".getBytes();

        String result = parser.extractAsMarkdown(
                new ByteArrayInputStream(invalidPdf), "test.pdf");

        assertNotNull(result);
    }

    @Test
    void nullFileName_shouldNotThrow() throws Exception {
        byte[] pdfBytes = ("%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
                + "xref\n0 4\n...\n%%EOF").getBytes();
        when(documentVisionExtractor.findPagesWithImages(any())).thenReturn(List.of());
        when(pdfTableExtractor.hasTables(any())).thenReturn(false);
        when(pdfTableExtractor.extractTables(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> {
            String result = parser.extractAsMarkdown(
                    new ByteArrayInputStream(pdfBytes), null);
            assertNotNull(result);
        });
    }

    // ========== 辅助方法 ==========

    private byte[] createMinimalPng() {
        byte[] png = new byte[67];
        png[0] = (byte) 0x89;
        png[1] = 0x50;
        png[2] = 0x4E;
        png[3] = 0x47;
        png[4] = 0x0D;
        png[5] = 0x0A;
        png[6] = 0x1A;
        png[7] = 0x0A;
        png[8] = 0x00; png[9] = 0x00; png[10] = 0x00; png[11] = 0x0D;
        png[12] = 0x49; png[13] = 0x48; png[14] = 0x44; png[15] = 0x52;
        return png;
    }
}
