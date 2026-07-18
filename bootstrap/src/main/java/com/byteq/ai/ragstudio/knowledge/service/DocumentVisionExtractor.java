package com.byteq.ai.ragstudio.knowledge.service;

import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文档视觉提取器
 * <p>
 * 当 Tika 传统文本提取效果不佳（文字过少或检测到扫描件特征）时，
 * 使用多模态大模型 (Qwen3.5-9B) 提取文档中的文字内容。
 * </p>
 *
 * <p>
 * 当前实现：
 * - 对图片文件（PNG/JPG/WebP）直接调用多模态模型提取文字
 * - 对 PDF/Office 等文档，需依赖 PDFBox 渲染为图片后再调用（待完善）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVisionExtractor {

    private final FileStorageService fileStorageService;

    // 文本长度低于此阈值时触发多模态提取
    private static final int MIN_TEXT_LENGTH = 50;

    /**
     * 判断是否需要视觉补充提取
     *
     * @param extractedText Tika 提取的文本
     * @return true 需要视觉提取
     */
    public boolean needsVisionExtraction(String extractedText) {
        if (extractedText == null) return true;
        String trimmed = extractedText.trim();
        return trimmed.length() < MIN_TEXT_LENGTH;
    }

    /**
     * 判断文件类型是否支持直接视觉提取
     *
     * @param mimeType 文件 MIME 类型
     * @return true 可直接作为图片传给多模态模型
     */
    public boolean isDirectVisionSupported(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/");
    }

    /**
     * 判断是否为 PDF（需要渲染后才能视觉提取）
     */
    public boolean isPdf(String mimeType) {
        return "application/pdf".equalsIgnoreCase(mimeType);
    }
}
