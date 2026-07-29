package com.byteq.ai.ragstudio.core.chunk;

import cn.hutool.core.util.IdUtil;
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
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageChunkGenerator {

    private static final int RENDER_DPI = 150;
    private static final int MAX_IMAGE_WIDTH = 1200;
    private static final int MAX_PDF_PAGES = 50;

    private final FileStorageService fileStorageService;

    public List<VectorChunk> generateFromPdf(byte[] pdfBytes, String bucketName, String baseKey, String docId) {
        List<VectorChunk> chunks = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.min(totalPages, MAX_PDF_PAGES);
            PDFRenderer renderer = new PDFRenderer(document);

            for (int i = 0; i < pagesToProcess; i++) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                    image = scaleImageIfNeeded(image);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "jpg", baos);
                    byte[] imageBytes = baos.toByteArray();

                    String key = baseKey + "/page_" + (i + 1) + ".jpg";
                    String s3Url = uploadToS3(bucketName, key, imageBytes, "image/jpeg");
                    String base64 = toBase64DataUri(imageBytes, "image/jpeg");

                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("content_type", "IMAGE");
                    metadata.put("image_url", s3Url);
                    metadata.put("image_base64", base64);
                    metadata.put("page_number", i);
                    metadata.put("doc_id", docId);

                    VectorChunk chunk = VectorChunk.builder()
                            .chunkId(IdUtil.getSnowflakeNextIdStr())
                            .index(i)
                            .content("")
                            .contentType("IMAGE")
                            .metadata(metadata)
                            .build();
                    chunks.add(chunk);
                } catch (Exception e) {
                    log.warn("渲染 PDF 页面失败: page={}, docId={}", i, docId, e);
                }
            }

            if (totalPages > MAX_PDF_PAGES) {
                log.warn("PDF 页数超过上限，仅处理前 {} 页（共 {} 页）", MAX_PDF_PAGES, totalPages);
            }
        } catch (Exception e) {
            log.error("从 PDF 生成图像块失败: docId={}", docId, e);
        }
        return chunks;
    }

    public VectorChunk generateSinglePage(PDDocument document, PDFRenderer renderer, int pageIndex,
                                          String bucketName, String baseKey, String docId) {
        try {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
            image = scaleImageIfNeeded(image);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", baos);
            byte[] imageBytes = baos.toByteArray();

            String key = baseKey + "/page_" + (pageIndex + 1) + ".jpg";
            String s3Url = uploadToS3(bucketName, key, imageBytes, "image/jpeg");
            String base64 = toBase64DataUri(imageBytes, "image/jpeg");

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("content_type", "IMAGE");
            metadata.put("image_url", s3Url);
            metadata.put("image_base64", base64);
            metadata.put("page_number", pageIndex);
            metadata.put("doc_id", docId);

            return VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(pageIndex)
                    .content("")
                    .contentType("IMAGE")
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.warn("渲染单页 PDF 失败: page={}, docId={}", pageIndex, docId, e);
            return null;
        }
    }

    public VectorChunk generateFromImage(byte[] imageBytes, String mimeType, String bucketName, String baseKey,
                                         String docId, int index) {
        String contentType = mimeType != null ? mimeType : "image/jpeg";
        String ext = contentType.contains("/") ? contentType.substring(contentType.lastIndexOf('/') + 1) : "jpg";

        try {
            String key = baseKey + "/image_" + index + "." + ext;
            String s3Url = uploadToS3(bucketName, key, imageBytes, contentType);
            String base64 = toBase64DataUri(imageBytes, contentType);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("content_type", "IMAGE");
            metadata.put("image_url", s3Url);
            metadata.put("image_base64", base64);
            metadata.put("doc_id", docId);

            return VectorChunk.builder()
                    .chunkId(IdUtil.getSnowflakeNextIdStr())
                    .index(index)
                    .content("")
                    .contentType("IMAGE")
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.error("从图片生成图像块失败: docId={}", docId, e);
            return null;
        }
    }

    private String uploadToS3(String bucketName, String key, byte[] imageBytes, String contentType) {
        var result = fileStorageService.uploadWithKey(bucketName, key, imageBytes, contentType);
        return result.getUrl() != null ? result.getUrl() : "";
    }

    private String toBase64DataUri(byte[] data, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(data);
        return "data:" + mimeType + ";base64," + base64;
    }

    private BufferedImage scaleImageIfNeeded(BufferedImage original) {
        int width = original.getWidth();
        if (width <= MAX_IMAGE_WIDTH) return original;

        int height = original.getHeight();
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
