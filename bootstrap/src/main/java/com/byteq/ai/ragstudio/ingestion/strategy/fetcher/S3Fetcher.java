package com.byteq.ai.ragstudio.ingestion.strategy.fetcher;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.util.MimeTypeDetector;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;

/**
 * S3 对象存储文档获取器
 * <p>
 * 负责从 S3 兼容的对象存储（如 RustFS、MinIO、AWS S3 等）中获取文档内容。
 * 路径格式为 {@code s3://bucket-name/path/to/document.pdf}。
 * </p>
 * <p>
 * 处理逻辑：
 * <ol>
 *   <li>验证 S3 路径格式是否正确（必须以 {@code s3://} 开头）</li>
 *   <li>通过 {@link FileStorageService} 打开文件流并读取全部字节</li>
 *   <li>自动检测文档的 MIME 类型</li>
 *   <li>从 S3 路径中提取文件名（路径的最后一段）</li>
 * </ol>
 * </p>
 *
 * @see FileStorageService
 */
@Component
@RequiredArgsConstructor
public class S3Fetcher implements DocumentFetcher {

    /**
     * 文件存储服务，用于访问 S3 对象存储
     */
    private final FileStorageService fileStorageService;

    /**
     * S3 文件最大允许大小（字节），默认 100MB。
     * 超过此限制的文件将被拒绝读取，以防止 OOM。
     */
    @Value("${ragstudio.fetcher.s3.max-file-size:104857600}")
    private long maxFileSize;

    @Override
    public SourceType supportedType() {
        return SourceType.S3;
    }

    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("S3路径不能为空");
        }

        if (!location.startsWith("s3://")) {
            throw new ServiceException("无效的S3路径格式，应以 s3:// 开头: " + location);
        }

        try {
            byte[] bytes;
            try (InputStream is = fileStorageService.openStream(location)) {
                // 读取 maxSize + 1 字节以检测文件是否超限
                bytes = is.readNBytes((int) maxFileSize + 1);
            }
            if (bytes.length > maxFileSize) {
                throw new ServiceException(
                        "S3文件大小超过限制: " + maxFileSize + " bytes (100MB), 来源: " + location);
            }

            String fileName = source.getFileName();
            if (!StringUtils.hasText(fileName)) {
                fileName = extractFileName(location);
            }

            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            return new FetchResult(bytes, mimeType, fileName);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("从S3读取文件失败: " + location + ", 错误: " + e.getMessage());
        }
    }

    /**
     * 从 S3 路径中提取文件名
     * <p>
     * 例如：{@code s3://biz/5fb28010e16c4083ab07ca41f29804b0.md}
     * 提取结果为 {@code 5fb28010e16c4083ab07ca41f29804b0.md}。
     * </p>
     *
     * @param location S3 对象存储路径
     * @return 文件名
     */
    private String extractFileName(String location) {
        int idx = location.lastIndexOf('/');
        return idx >= 0 ? location.substring(idx + 1) : location;
    }
}
