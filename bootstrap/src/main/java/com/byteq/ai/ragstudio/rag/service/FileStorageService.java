package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;

public interface FileStorageService {

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     */
    StoredFileDTO upload(String bucketName, MultipartFile file);

    /**
     * 上传文件到指定前缀目录（流式，低内存）
     * <p>
     * 例如 prefix="document/" → 生成的 key 为 document/uuid.ext
     */
    StoredFileDTO upload(String bucketName, String prefix, MultipartFile file);

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     */
    StoredFileDTO upload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 上传文件到指定前缀目录（流式，低内存）
     */
    StoredFileDTO upload(String bucketName, String prefix, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 上传文件（流式，低内存）
     * <p>
     * 通过 S3Presigner 预签名 URL + HttpURLConnection 流式上传，堆内存开销近似为零
     * 适用于大文件上传、高并发场景。不具备 SDK 内置的自动重试能力，失败需业务层自行重试
     */
    StoredFileDTO upload(String bucketName, byte[] content, String originalFilename, String contentType);

    /**
     * 上传文件到指定前缀目录（流式，低内存）
     */
    StoredFileDTO upload(String bucketName, String prefix, byte[] content, String originalFilename, String contentType);

    /**
     * 上传文件（SDK 原生，带自动重试）
     * <p>
     * 通过 AWS SDK 的 putObject 上传，具备 SDK 内置的自动重试机制（网络抖动、超时等场景自动重发）
     * 代价是 SDK 上传管线会将 payload 缓冲到堆内存（实测 30MB 文件约 100MB 堆增量）
     * 适用于小文件上传或对重试可靠性要求高、但对内存不敏感的场景。
     */
    StoredFileDTO reliableUpload(String bucketName, InputStream content, long size, String originalFilename, String contentType);

    /**
     * 按指定 S3 Key 上传文件（流式，低内存）
     * <p>
     * 跳过随机 UUID 命名，直接使用调用方指定的 key。
     * 适用于需要可预测路径的场景（如图像分块存储）。
     * 不具备 SDK 内置的自动重试能力，失败需业务层自行重试。
     * </p>
     *
     * @param bucketName      S3 bucket 名称
     * @param key             S3 对象 Key（完整路径，如 document/col1/doc123/page_0.jpg）
     * @param content         文件字节内容
     * @param contentType     MIME 类型
     * @return 上传结果，包含生成的文件 URL
     */
    StoredFileDTO uploadWithKey(String bucketName, String key, byte[] content, String contentType);

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

    /**
     * 生成 S3 预签名 GET URL（临时可访问的 HTTP URL）
     * <p>
     * 将 s3://bucket/key 格式的内部 URL 转换为带签名的 HTTP URL，
     * 浏览器和 LLM API 可以通过此 URL 直接访问图片内容。
     * 适用于前端图片渲染、多模态模型图片输入等场景。
     * </p>
     *
     * @param s3Url s3:// 协议的内部 URL
     * @return 预签名 HTTP URL，有效期 1 小时
     */
    String generatePresignedGetUrl(String s3Url);

    /**
     * 生成 S3 预签名 GET URL（指定过期时长）
     * <p>
     * 适用于文档预览等需要精确控制过期时间的场景（如短 TTL 预览链接）。
     * </p>
     *
     * @param s3Url    s3:// 协议的内部 URL
     * @param duration 过期时长（如 Duration.ofMinutes(5)）
     * @return 预签名 HTTP URL
     */
    String generatePresignedGetUrl(String s3Url, Duration duration);

    /**
     * 生成 S3 预签名 GET URL（指定过期时长并覆盖响应 Content-Type）
     * <p>
     * 用于文本文件预览，设置 charset=utf-8 避免中文乱码。
     * S3 会在返回文件时使用此 Content-Type 覆盖原始存储的元数据。
     * </p>
     *
     * @param s3Url               s3:// 协议的内部 URL
     * @param duration            过期时长
     * @param responseContentType 覆盖的 Content-Type（如 "text/plain; charset=utf-8"）
     * @return 预签名 HTTP URL
     */
    String generatePresignedGetUrl(String s3Url, Duration duration, String responseContentType);
}
