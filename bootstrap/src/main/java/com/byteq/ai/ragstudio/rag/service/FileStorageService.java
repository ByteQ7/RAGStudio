package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

public interface FileStorageService {

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     */
    StoredFileDTO upload(String bucketName, MultipartFile file);

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     */
    StoredFileDTO upload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     */
    StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType);

    /**
     * 上传文件（SDK 原生，带自动重试）
     * <p>
     * 通过 AWS SDK 的 putObject 上传，具备 SDK 内置的自动重试机制（网络抖动、超时等场景自动重发）
     * 代价是 SDK 上传管线会将 payload 缓冲到堆内存（实测 30MB 文件约 100MB 堆增量）
     * 适用于小文件上传或对重试可靠性要求高、但对内存不敏感的场景。
     */
    StoredFileDTO reliableUpload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 通过文件 URL 打开输入流
     * <p>
     * 根据文件的访问 URL 获取文件的输入流，用于读取已上传的文件内容。
     * </p>
     *
     * @param url 文件的访问 URL
     * @return 文件内容的输入流
     */
    InputStream openStream(String url);

    /**
     * 根据文件 URL 删除已上传的文件
     *
     * @param url 文件的访问 URL
     */
    void deleteByUrl(String url);
}
