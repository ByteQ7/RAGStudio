package com.byteq.ai.ragstudio.knowledge.service;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.rag.constant.RAGConstant;
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
import java.util.List;
import java.util.UUID;

/**
 * 文档视觉提取器
 * <p>
 * 当 Tika 传统文本提取效果不佳（文字过少或检测到扫描件特征）时，
 * 使用多模态大模型提取文档中的文字内容。
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

    // PDF 渲染最大页数（防止 OOM）
    private static final int MAX_PDF_PAGES = 10;

    // 图片最大宽度
    private static final int MAX_IMAGE_WIDTH = 1200;

    /**
     * 判断是否需要视觉补充提取
     */
    public boolean needsVisionExtraction(String extractedText) {
        if (extractedText == null) return true;
        String trimmed = extractedText.trim();
        return trimmed.length() < MIN_TEXT_LENGTH;
    }

    /**
     * 判断文件类型是否支持直接视觉提取
     */
    public boolean isDirectVisionSupported(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/");
    }

    /**
     * 判断是否为 PDF
     */
    public boolean isPdf(String mimeType) {
        return "application/pdf".equalsIgnoreCase(mimeType);
    }

    /**
     * 使用多模态模型从文件中提取文字
     *
     * @param fileUrl  文件 URL (S3)
     * @param mimeType 文件 MIME 类型
     * @param fileName 文件名
     * @return 提取的文字内容
     */
    public String extractTextWithVision(String fileUrl, String mimeType, String fileName) {
        String docImageModelId = defaultModelConfigService.getModelId("doc_image");
        if (docImageModelId == null) {
            log.warn("未配置 doc_image 默认模型，跳过视觉提取: fileUrl={}", fileUrl);
            return "";
        }

        List<String> imageUrls = new ArrayList<>();

        try {
            if (isPdf(mimeType)) {
                // PDF 渲染为图片
                try (InputStream is = fileStorageService.openStream(fileUrl)) {
                    byte[] pdfBytes = is.readAllBytes();
                    imageUrls.addAll(renderPdfToImages(pdfBytes, fileName));
                }
            } else if (isDirectVisionSupported(mimeType)) {
                // 图片文件直接使用
                String presignedUrl = fileStorageService.generatePresignedGetUrl(fileUrl);
                imageUrls.add(presignedUrl);
            } else {
                log.warn("不支持视觉提取的文件类型: mimeType={}, fileUrl={}", mimeType, fileUrl);
                return "";
            }

            if (imageUrls.isEmpty()) {
                log.warn("视觉提取未产生可处理的图片: fileUrl={}", fileUrl);
                return "";
            }

            // 构建多模态请求：将图片 URL 附在 user message 上
            ChatMessage userMsg = ChatMessage.user(
                    "请提取以下文档中的所有文字内容，以 Markdown 格式输出。保持原有结构和顺序，表格请用 Markdown 表格语法（| 列名 | 列名 |\\n|--- |--- |\\n| 内容 |），标题用 # 号标记，列表保持层级。"
            );
            userMsg.setImageUrls(imageUrls);

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(userMsg))
                    .build();

            // 调用多模态模型（指定 doc_image 场景的默认模型）
            return llmService.chat(request, docImageModelId);
        } catch (Exception e) {
            log.error("视觉提取失败: fileUrl={}", fileUrl, e);
            return "";
        }
    }

    /**
     * 将 PDF 渲染为图片并上传到 S3，返回图片访问 URL 列表
     */
    private List<String> renderPdfToImages(byte[] pdfBytes, String fileName) throws Exception {
        List<String> urls = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            int pagesToRender = Math.min(totalPages, MAX_PDF_PAGES);
            PDFRenderer renderer = new PDFRenderer(document);

            for (int i = 0; i < pagesToRender; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                image = scaleImageIfNeeded(image);

                // 转为 JPEG 字节流
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "jpg", baos);
                baos.flush();
                byte[] imageBytes = baos.toByteArray();

                // 上传到 S3
                String objectName = RAGConstant.S3_DOCUMENT_PREFIX + "/vision/"
                        + (fileName != null ? fileName.replaceAll("[^a-zA-Z0-9._-]", "_") : "doc")
                        + "_page_" + (i + 1) + "_" + UUID.randomUUID() + ".jpg";

                String s3Url = fileStorageService.upload(
                        RAGConstant.S3_BUCKET_NAME,
                        imageBytes,
                        objectName,
                        "image/jpeg"
                ).getUrl();

                // 生成预签名 URL 供 LLM 读取
                String presignedUrl = fileStorageService.generatePresignedGetUrl(s3Url);
                urls.add(presignedUrl);

                log.debug("PDF 页面渲染完成: page={}/{}, size={}KB", i + 1, totalPages, imageBytes.length / 1024);
            }

            if (totalPages > MAX_PDF_PAGES) {
                log.info("PDF 页数超过限制，仅处理前 {} 页（共 {} 页）", MAX_PDF_PAGES, totalPages);
            }
        }

        return urls;
    }

    /**
     * 缩放图片到最大宽度，防止图片过大
     */
    private BufferedImage scaleImageIfNeeded(BufferedImage original) {
        int width = original.getWidth();
        int height = original.getHeight();

        if (width <= MAX_IMAGE_WIDTH) {
            return original;
        }

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
