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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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

    /**
     * 本地文件最大允许大小（字节），默认 100MB。
     * 超过此限制的文件将被拒绝读取，以防止 OOM。
     */
    @Value("${ragstudio.fetcher.local.max-file-size:104857600}")
    private long maxFileSize;

    /**
     * 允许访问的本地文件基目录列表（逗号分隔）。
     * 如果为空则不限制基目录，但仍会拒绝包含 ".." 的路径。
     * 示例: "/data/docs,/opt/uploads"
     */
    @Value("${ragstudio.fetcher.local.allowed-base-dirs:}")
    private String allowedBaseDirsConfig;

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
                    bytes = readWithLimit(is, maxFileSize, location);
                }
                if (!StringUtils.hasText(fileName)) {
                    fileName = extractFileName(location);
                }
            } else {
                // 本地文件系统路径
                Path path = location.startsWith("file://")
                        ? Path.of(URI.create(location))
                        : Path.of(location);

                // 安全检查：拒绝包含 ".." 的路径段，防止路径穿越
                validateLocalPath(path);

                // 解析为规范路径，确保不逃逸允许的基目录
                Path realPath = path.toRealPath();

                // 检查文件大小，防止 OOM
                long fileSize = Files.size(realPath);
                if (fileSize > maxFileSize) {
                    throw new ServiceException("文件大小超过限制: " + fileSize + " bytes, 最大允许: " + maxFileSize + " bytes");
                }

                bytes = Files.readAllBytes(realPath);
                if (!StringUtils.hasText(fileName) && realPath.getFileName() != null) {
                    fileName = realPath.getFileName().toString();
                }
            }
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            return new FetchResult(bytes, mimeType, fileName);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 校验本地文件路径的安全性
     * <p>
     * 拒绝包含 ".." 路径段的路径，并检查路径是否在允许的基目录范围内。
     * </p>
     *
     * @param path 待校验的路径
     * @throws ServiceException 如果路径不合法
     */
    private void validateLocalPath(Path path) {
        // 拒绝包含 ".." 的路径段，防止路径穿越攻击
        for (Path component : path) {
            if ("..".equals(component.toString())) {
                throw new ServiceException("文件路径不允许包含 '..' 段: " + path);
            }
        }

        // 如果配置了允许的基目录，则验证路径是否在其范围内
        List<String> allowedDirs = parseAllowedBaseDirs();
        if (!allowedDirs.isEmpty()) {
            Path normalized = path.toAbsolutePath().normalize();
            boolean allowed = false;
            for (String dir : allowedDirs) {
                Path baseDir = Path.of(dir).toAbsolutePath().normalize();
                if (normalized.startsWith(baseDir)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new ServiceException("文件路径不在允许的基目录范围内: " + path);
            }
        }
    }

    /**
     * 解析配置的允许基目录列表
     *
     * @return 基目录列表，如果未配置则返回空列表
     */
    private List<String> parseAllowedBaseDirs() {
        if (!StringUtils.hasText(allowedBaseDirsConfig)) {
            return List.of();
        }
        return Arrays.stream(allowedBaseDirsConfig.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 从输入流中读取数据，并施加大小限制以防止 OOM
     *
     * @param is       输入流
     * @param maxSize  最大允许字节数
     * @param location 资源标识（用于错误信息）
     * @return 读取的字节数组
     * @throws Exception 如果读取失败或超出大小限制
     */
    private byte[] readWithLimit(InputStream is, long maxSize, String location) throws Exception {
        byte[] data = is.readNBytes((int) maxSize + 1);
        if (data.length > maxSize) {
            throw new ServiceException("文件大小超过限制: " + maxSize + " bytes, 来源: " + location);
        }
        return data;
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
