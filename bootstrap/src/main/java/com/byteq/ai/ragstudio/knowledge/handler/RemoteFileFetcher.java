package com.byteq.ai.ragstudio.knowledge.handler;

import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.ingestion.util.HttpClientHelper;
import com.byteq.ai.ragstudio.ingestion.util.SsrfGuard;
import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * 远程文件拉取服务
 * 封装远程文件的 HEAD 预检、流式下载、变更检测等逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteFileFetcher {

    private final HttpClientHelper httpClientHelper;
    private final FileStorageService fileStorageService;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    /**
     * 流式拉取远程文件并上传到存储（用于文档上传场景）
     * <p>
     * 处理流程：
     * 1. SSRF 安全校验 + HEAD 预检获取文件大小
     * 2. 校验文件大小是否超限
     * 3. 流式下载并落临时文件，再以实际字节数上传到文件存储
     * </p>
     *
     * @param bucketName 存储桶名称
     * @param url        远程文件 URL
     * @return 上传后的文件存储信息
     */
    public StoredFileDTO fetchAndStore(String bucketName, String url) {
        return fetchAndStore(bucketName, null, url);
    }

    /**
     * 流式拉取远程文件并上传到指定前缀目录（用于文档上传场景）
     *
     * @param bucketName 存储桶名称
     * @param prefix     目录前缀（如 "document"）
     * @param url        远程文件 URL
     * @return 上传后的文件存储信息
     */
    public StoredFileDTO fetchAndStore(String bucketName, String prefix, String url) {
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        SsrfGuard.validate(url);
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);
        Long headContentLength = headResponse == null ? null : headResponse.contentLength();
        checkSizeLimit(maxBytes, headContentLength);

        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            String fileName = firstHasText(response.fileName(), headResponse == null ? null : headResponse.fileName(), "remote-file");
            String contentType = firstHasText(response.contentType(), headResponse == null ? null : headResponse.contentType(), null);
            // 部分源站的 Content-Length/HEAD 响应并不可靠，固定长度上传会在字节数不一致时失败
            // 远程导入统一先落临时文件，以实际读取到的字节数作为上传大小
            return uploadViaTemp(bucketName, prefix, response.bodyStream(), fileName, contentType, maxBytes);
        }
    }

    /**
     * 流式拉取远程文件并检测变更（用于定时刷新场景）
     * <p>
     * 处理流程：
     * 1. SSRF 安全校验 + HEAD 预检，比对 ETag/Last-Modified 判断是否有变更
     * 2. 若无变更直接返回 skipped 结果
     * 3. 有变更则流式下载到临时文件并计算 SHA-256 哈希
     * 4. 比对内容哈希，若内容未变则删除临时文件返回 skipped
     * 5. 内容确实变化则返回 changed 结果（包含临时文件路径，由调用方负责清理）
     * </p>
     * <p>返回的 RemoteFetchResult 实现了 AutoCloseable，调用方必须用 try-with-resources 管理生命周期</p>
     *
     * @param url              远程文件 URL
     * @param lastEtag         上次记录的 ETag
     * @param lastModified     上次记录的 Last-Modified
     * @param lastContentHash  上次记录的内容 SHA-256 哈希
     * @param fallbackFileName 当响应头无文件名时的回退文件名
     * @return 远程拉取结果，包含是否变更及临时文件等信息
     */
    public RemoteFetchResult fetchIfChanged(String url, String lastEtag, String lastModified,
                                            String lastContentHash, String fallbackFileName) {
        long maxBytes = maxFileSize.toBytes();
        url = url.trim();
        SsrfGuard.validate(url);
        HttpClientHelper.HttpHeadResponse headResponse = tryHead(url);

        if (headResponse != null) {
            checkSizeLimit(maxBytes, headResponse.contentLength());
            String etag = trimOrNull(headResponse.etag());
            String headLastModified = trimOrNull(headResponse.lastModified());
            boolean etagMatch = StringUtils.hasText(etag) && etag.equals(trimOrNull(lastEtag));
            boolean modifiedMatch = StringUtils.hasText(headLastModified) && headLastModified.equals(trimOrNull(lastModified));
            if (etagMatch || modifiedMatch) {
                return RemoteFetchResult.skipped("远程文件未变化", etag, headLastModified, lastContentHash);
            }
        }

        Path tempFile = null;
        try (HttpClientHelper.HttpFetchStream response = httpClientHelper.openStream(url, Map.of(), maxBytes)) {
            tempFile = Files.createTempFile("knowledge-schedule-", ".tmp");
            CopyResult copyResult = copyWithLimitAndDigest(response.bodyStream(), tempFile, maxBytes);
            if (copyResult.size == 0) {
                deleteTempFileQuietly(tempFile);
                throw new ClientException("远程文件内容为空");
            }

            String hash = copyResult.sha256Hex;
            String etag = firstHasText(trimOrNull(response.etag()), headResponse == null ? null : trimOrNull(headResponse.etag()), null);
            String fetchLastModified = firstHasText(trimOrNull(response.lastModified()), headResponse == null ? null : trimOrNull(headResponse.lastModified()), null);

            if (StringUtils.hasText(hash) && hash.equals(trimOrNull(lastContentHash))) {
                deleteTempFileQuietly(tempFile);
                return RemoteFetchResult.skipped("内容哈希未变化", etag, fetchLastModified, hash);
            }

            String fileName = StringUtils.hasText(response.fileName()) ? response.fileName() : fallbackFileName;
            return RemoteFetchResult.changed(tempFile, copyResult.size, response.contentType(), fileName, hash, etag, fetchLastModified);
        } catch (IOException e) {
            deleteTempFileQuietly(tempFile);
            throw new ServiceException("远程文件拉取失败: " + e.getMessage());
        } catch (RuntimeException e) {
            deleteTempFileQuietly(tempFile);
            throw e;
        }
    }

    // 尝试发送 HEAD 请求获取远程文件元信息，失败时返回 null 以便降级为直接下载
    private HttpClientHelper.HttpHeadResponse tryHead(String url) {
        try {
            return httpClientHelper.head(url, Map.of());
        } catch (Exception e) {
            log.debug("HEAD 获取失败，改为直接下载: {}", url, e);
            return null;
        }
    }

    // 校验远程文件大小是否超过允许的最大值
    private void checkSizeLimit(long maxBytes, Long contentLength) {
        if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
            throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
        }
    }

    // 将远程流先写入临时文件，再以实际大小上传到文件存储，最后清理临时文件
    private StoredFileDTO uploadViaTemp(String bucketName, String prefix, InputStream remoteStream, String fileName,
                                        String contentType, long maxBytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("knowledge-upload-", ".tmp");
            long size = copyWithLimit(remoteStream, tempFile, maxBytes);
            if (size == 0) {
                throw new ClientException("远程文件内容为空");
            }
            try (InputStream tempInputStream = Files.newInputStream(tempFile)) {
                return fileStorageService.upload(bucketName, prefix, tempInputStream, size, fileName, contentType);
            }
        } catch (IOException e) {
            throw new ServiceException("远程文件上传失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.warn("删除远程上传临时文件失败: {}", tempFile, e);
                }
            }
        }
    }

    // 将输入流复制到临时文件，同时校验总字节数不超过上限，返回实际写入字节数
    private long copyWithLimit(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        long total = 0;
        try (var outputStream = Files.newOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                }
                outputStream.write(buffer, 0, len);
            }
            return total;
        }
    }

    // 将输入流复制到临时文件并同时计算 SHA-256 摘要，返回写入字节数和哈希值
    private CopyResult copyWithLimitAndDigest(InputStream inputStream, Path tempFile, long maxBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream outputStream = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    total += len;
                    if (maxBytes > 0 && total > maxBytes) {
                        throw new ClientException("远程文件大小超过限制: " + maxBytes + " bytes");
                    }
                    outputStream.write(buffer, 0, len);
                    digest.update(buffer, 0, len);
                }
            }
            return new CopyResult(total, hexEncode(digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("SHA-256 算法不可用");
        }
    }

    private record CopyResult(long size, String sha256Hex) {
    }

    // 将字节数组编码为十六进制字符串
    private static String hexEncode(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            String value = Integer.toHexString(0xff & b);
            if (value.length() == 1) {
                hex.append('0');
            }
            hex.append(value);
        }
        return hex.toString();
    }

    // 安全删除临时文件，失败时仅记录警告日志不抛出异常
    private void deleteTempFileQuietly(Path tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("删除临时文件失败: {}", tempFile, e);
            }
        }
    }

    // 从多个候选值中返回第一个非空非空白字符串，全部为空时返回 null
    private String firstHasText(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) return v;
        }
        return null;
    }

    // 去除字符串两端空白，无有效内容时返回 null
    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public static final class RemoteFetchResult implements AutoCloseable {

        private final boolean changed;
        private Path tempFile;
        private final long size;
        private final String contentType;
        private final String fileName;
        private final String contentHash;
        private final String etag;
        private final String lastModified;
        private final String message;

        private RemoteFetchResult(boolean changed, Path tempFile, long size, String contentType,
                                  String fileName, String contentHash, String etag,
                                  String lastModified, String message) {
            this.changed = changed;
            this.tempFile = tempFile;
            this.size = size;
            this.contentType = contentType;
            this.fileName = fileName;
            this.contentHash = contentHash;
            this.etag = etag;
            this.lastModified = lastModified;
            this.message = message;
        }

        /**
         * 创建"未变更"结果，表示远程文件未发生变化无需重新处理
         */
        public static RemoteFetchResult skipped(String message, String etag, String lastModified, String contentHash) {
            return new RemoteFetchResult(false, null, 0, null, null, contentHash, etag, lastModified, message);
        }

        /**
         * 创建"已变更"结果，表示远程文件已更新，包含下载到临时文件的完整信息
         */
        public static RemoteFetchResult changed(Path tempFile, long size, String contentType, String fileName,
                                                 String contentHash, String etag, String lastModified) {
            return new RemoteFetchResult(true, tempFile, size, contentType, fileName, contentHash, etag, lastModified, null);
        }

        public boolean changed() { return changed; }
        public Path tempFile() { return tempFile; }
        public long size() { return size; }
        public String contentType() { return contentType; }
        public String fileName() { return fileName; }
        public String contentHash() { return contentHash; }
        public String etag() { return etag; }
        public String lastModified() { return lastModified; }
        public String message() { return message; }

        @Override
        public void close() {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    // best-effort cleanup
                }
                tempFile = null;
            }
        }
    }
}
