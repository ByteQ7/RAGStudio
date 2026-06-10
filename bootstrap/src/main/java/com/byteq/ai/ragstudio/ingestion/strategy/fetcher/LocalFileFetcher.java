package com.byteq.ai.ragstudio.ingestion.strategy.fetcher;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.util.MimeTypeDetector;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地文件获取器
 * <p>
 * 负责从本地文件系统或对象存储（S3 协议）中读取文件内容。
 * 支持以下路径格式：
 * <ul>
 *   <li>本地绝对路径：如 {@code /data/docs/report.pdf}</li>
 *   <li>file:// 协议：如 {@code file:///data/docs/report.pdf}</li>
 *   <li>S3 协议路径：如 {@code s3://bucket/docs/report.pdf}</li>
 * </ul>
 * </p>
 * <p>
 * 注意：此类已标记为 {@link Deprecated}，建议使用 {@link S3Fetcher} 处理 S3 路径。
 * </p>
 *
 * @deprecated 功能已拆分为 {@link S3Fetcher}，后续版本将移除
 */
@Component
@RequiredArgsConstructor
@Deprecated
public class LocalFileFetcher implements DocumentFetcher {

    /**
     * 文件存储服务，用于访问 S3 等远程存储
     */
    private final FileStorageService fileStorageService;

    @Override
    public SourceType supportedType() {
        return SourceType.FILE;
    }

    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("文件路径不能为空");
        }
        try {
            byte[] bytes;
            String fileName = source.getFileName();
            // 支持 S3 协议路径
            if (location.startsWith("s3://")) {
                try (InputStream is = fileStorageService.openStream(location)) {
                    bytes = is.readAllBytes();
                }
                if (!StringUtils.hasText(fileName)) {
                    fileName = extractFileName(location);
                }
            } else {
                // 本地文件系统路径
                Path path = location.startsWith("file://")
                        ? Path.of(URI.create(location))
                        : Path.of(location);
                bytes = Files.readAllBytes(path);
                if (!StringUtils.hasText(fileName) && path.getFileName() != null) {
                    fileName = path.getFileName().toString();
                }
            }
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            return new FetchResult(bytes, mimeType, fileName);
        } catch (Exception e) {
            throw new ServiceException("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 从 S3 路径中提取文件名
     *
     * @param location S3 路径
     * @return 文件名
     */
    private String extractFileName(String location) {
        int idx = location.lastIndexOf('/');
        return idx >= 0 ? location.substring(idx + 1) : location;
    }
}
