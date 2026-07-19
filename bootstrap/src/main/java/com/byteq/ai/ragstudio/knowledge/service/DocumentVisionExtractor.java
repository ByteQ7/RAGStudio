package com.byteq.ai.ragstudio.knowledge.service;

import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文档视觉提取器
 * <p>
 * 当 Tika 传统文本提取效果不佳（文字过少或文档含嵌入图片）时，
 * 使用多模态大模型提取文档中的文字内容。
 * </p>
 * <p>
 * 支持的文件类型：
 * <ul>
 *   <li>图片文件（PNG/JPG/WebP）— 直接传入 Base64</li>
 *   <li>PDF — 渲染每页为图片再传入 Base64</li>
 *   <li>ODT/DOCX/PPTX — 从 ZIP 包中提取嵌入图片再传入 Base64</li>
 * </ul>
 * 所有图片通过 Base64 data URI 传递给多模态模型，不额外上传到 S3。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVisionExtractor {

    private final FileStorageService fileStorageService;
    private final DefaultModelConfigService defaultModelConfigService;
    private final LLMService llmService;

    // 文本长度低于此阈值时触发多模态提取
    private static final int MIN_TEXT_LENGTH = 50;

    // PDF 渲染最大页数
    private static final int MAX_PDF_PAGES = 10;

    // 图片最大宽度
    private static final int MAX_IMAGE_WIDTH = 1200;

    // 支持嵌入图片的文档类型（ZIP-based 格式）
    private static final List<String> DOC_TYPES_WITH_IMAGES = List.of(
            "odt", "ods", "odp",
            "docx", "xlsx", "pptx",
            "doc", "xls", "ppt"
    );

    // ==================== 判断方法 ====================

    /**
     * 判断是否需要视觉补充提取
     *
     * @param extractedText Tika 提取的文本
     * @return true 需要视觉提取
     */
    public boolean needsVisionExtraction(String extractedText) {
        if (extractedText == null) return true;
        return extractedText.trim().length() < MIN_TEXT_LENGTH;
    }

    /**
     * 判断是否需要视觉提取（含文档类型检测）
     *
     * @param extractedText Tika 提取的文本
     * @param fileType      文件类型标识（如 pdf, odt, docx）
     * @return true 需要视觉提取
     */
    public boolean needsVisionExtraction(String extractedText, String fileType) {
        if (needsVisionExtraction(extractedText)) return true;
        // 即使文本量充足，如果文档类型可能含嵌入图片，也尝试视觉提取
        return mayContainEmbeddedImages(fileType);
    }

    /**
     * 判断文件类型是否可能包含嵌入图片
     */
    public boolean mayContainEmbeddedImages(String fileType) {
        if (fileType == null) return false;
        String ft = fileType.toLowerCase();
        return DOC_TYPES_WITH_IMAGES.contains(ft)
                || ft.equals("pdf")
                || ft.startsWith("image/");
    }

    /**
     * 判断是否为 PDF
     */
    public boolean isPdf(String fileType) {
        return "pdf".equalsIgnoreCase(fileType)
                || "application/pdf".equalsIgnoreCase(fileType);
    }

    // ==================== 核心提取方法 ====================

    /**
     * 使用多模态模型从文件中提取文字
     *
     * @param fileUrl  文件 URL (S3)
     * @param fileType 文件类型标识
     * @param fileName 文件名
     * @return 提取的文字内容
     */
    public String extractTextWithVision(String fileUrl, String fileType, String fileName) {
        String docImageModelId = defaultModelConfigService.getModelId("doc_image");
        if (docImageModelId == null) {
            log.warn("未配置 doc_image 默认模型，跳过视觉提取: fileUrl={}", fileUrl);
            return "";
        }

        try {
            byte[] fileBytes = readFileBytes(fileUrl);
            List<String> imageDataUris = new ArrayList<>();

            if (isPdf(fileType)) {
                imageDataUris.addAll(renderPdfToDataUris(fileBytes));
            } else if (fileType != null && fileType.startsWith("image/")) {
                String dataUri = toBase64DataUri(fileBytes, fileType);
                imageDataUris.add(dataUri);
            } else if (isZipBasedFormat(fileType)) {
                imageDataUris.addAll(extractImagesFromZip(fileBytes));
            } else {
                log.warn("不支持视觉提取的文件类型: fileType={}, fileUrl={}", fileType, fileUrl);
                return "";
            }

            if (imageDataUris.isEmpty()) {
                log.warn("视觉提取未产生可处理的图片: fileUrl={}, fileName={}", fileUrl, fileName);
                return "";
            }

            // 构建多模态请求：将 Base64 data URI 附在 user message 的 imageUrls 上
            ChatMessage userMsg = ChatMessage.user(
                    "请提取以下图片中的所有文字内容，以 Markdown 格式输出。保持原有结构和顺序，表格请用 Markdown 表格语法（| 列名 | 列名 |\\n|--- |--- |\\n| 内容 |），标题用 # 号标记，列表保持层级。"
            );
            userMsg.setImageUrls(imageDataUris);

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(userMsg))
                    .build();

            return llmService.chat(request, docImageModelId);
        } catch (Exception e) {
            log.error("视觉提取失败: fileUrl={}", fileUrl, e);
            return "";
        }
    }

    // ==================== 从 S3 读取文件 ====================

    private byte[] readFileBytes(String fileUrl) {
        try (InputStream is = fileStorageService.openStream(fileUrl)) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + fileUrl, e);
        }
    }

    // ==================== PDF 渲染 ====================

    private List<String> renderPdfToDataUris(byte[] pdfBytes) throws Exception {
        List<String> dataUris = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            int pagesToRender = Math.min(totalPages, MAX_PDF_PAGES);
            PDFRenderer renderer = new PDFRenderer(document);

            for (int i = 0; i < pagesToRender; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                image = scaleImageIfNeeded(image);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", baos);
                byte[] imageBytes = baos.toByteArray();

                dataUris.add(toBase64DataUri(imageBytes, "image/jpeg"));
                log.debug("PDF 页面渲染完成: page={}/{}", i + 1, totalPages);
            }

            if (totalPages > MAX_PDF_PAGES) {
                log.info("PDF 页数超过限制，仅处理前 {} 页（共 {} 页）", MAX_PDF_PAGES, totalPages);
            }
        }
        return dataUris;
    }

    // ==================== ZIP 嵌入图片提取（ODT/DOCX/PPTX） ====================

    /**
     * 从 ZIP 格式的文档中提取嵌入图片，返回 Base64 data URI 列表
     * <p>
     * ODT/DOCX/PPTX 都是 ZIP 包，图片文件位于特定目录：
     * - ODT:  Pictures/  或 media/
     * - DOCX: word/media/
     * - PPTX: ppt/media/
     */
    private List<String> extractImagesFromZip(byte[] zipBytes) {
        List<String> dataUris = new ArrayList<>();
        List<String> imagePaths = List.of(
                "Pictures/", "media/",
                "word/media/", "ppt/media/",
                "META-INF/"  // 排除
        );

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/').toLowerCase();
                // 跳过 META-INF
                if (name.startsWith("meta-inf/")) continue;

                // 检查是否在图片目录中
                boolean isImage = false;
                for (String imgPath : imagePaths) {
                    if (name.startsWith(imgPath.toLowerCase())) {
                        isImage = true;
                        break;
                    }
                }
                // 也检测图片扩展名
                if (!isImage) {
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
                    isImage = List.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg").contains(ext);
                }

                if (!isImage) continue;

                byte[] imageBytes = zis.readAllBytes();
                if (imageBytes.length == 0) continue;

                // 检测 MIME
                String mime = detectImageMime(name, imageBytes);
                String dataUri = toBase64DataUri(imageBytes, mime);
                dataUris.add(dataUri);
                log.debug("从 ZIP 提取嵌入图片: {}, size={}KB", name, imageBytes.length / 1024);
            }
        } catch (Exception e) {
            log.warn("从 ZIP 提取嵌入图片失败: {}", e.getMessage());
        }

        if (dataUris.isEmpty()) {
            log.warn("文档中未找到嵌入图片");
        }

        return dataUris;
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否为基于 ZIP 的文档格式
     */
    private boolean isZipBasedFormat(String fileType) {
        if (fileType == null) return false;
        String ft = fileType.toLowerCase();
        return DOC_TYPES_WITH_IMAGES.contains(ft);
    }

    /**
     * 将字节数组转为 Base64 data URI
     */
    private String toBase64DataUri(byte[] data, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(data);
        return "data:" + mimeType + ";base64," + base64;
    }

    /**
     * 根据文件名和文件头检测图片 MIME
     */
    private String detectImageMime(String fileName, byte[] bytes) {
        if (bytes.length < 4) return "image/png";
        // 根据 magic number 判断
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) return "image/jpeg";
        if (bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50) return "image/png";
        if (bytes[0] == (byte) 0x47 && bytes[1] == (byte) 0x49) return "image/gif";
        if (bytes[0] == (byte) 0x42 && bytes[1] == (byte) 0x4D) return "image/bmp";
        if (bytes[0] == (byte) 0x52 && bytes[1] == (byte) 0x49) return "image/webp";
        // fallback 到扩展名
        String name = fileName != null ? fileName.toLowerCase() : "";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    /**
     * 缩放图片到最大宽度
     */
    private BufferedImage scaleImageIfNeeded(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();
        if (width <= MAX_IMAGE_WIDTH) return original;

        int newWidth = MAX_IMAGE_WIDTH;
        int newHeight = (int) ((double) height * newWidth / width);
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }
}
