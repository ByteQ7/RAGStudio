package com.byteq.ai.ragstudio.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * RustFS S3 客户端配置类
 * 用于配置和初始化与 RustFS 对象存储服务交互的 S3 客户端
 */
@Configuration
public class RestFSS3Config {

    /**
     * 创建 S3 客户端 Bean
     * <p>
     * 用于与 RustFS 对象存储服务进行交互，执行文件的上传、下载、删除等操作。
     * 采用路径风格寻址（forcePathStyle），兼容 MinIO 等 S3 兼容存储。
     * </p>
     *
     * @param rustfsUrl       RustFS 服务地址
     * @param accessKeyId     访问密钥 ID
     * @param secretAccessKey 访问密钥
     * @return S3 客户端实例
     */
    @Bean
    public S3Client s3Client(@Value("${rustfs.url}") String rustfsUrl,
                             @Value("${rustfs.access-key-id}") String accessKeyId,
                             @Value("${rustfs.secret-access-key}") String secretAccessKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(rustfsUrl))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                        )
                )
                .forcePathStyle(true)
                .build();
    }

    /**
     * S3 预签名器，用于生成预签名 URL
     * 签名在 URL query 参数中完成，配合 HttpURLConnection 实现零堆内存的流式文件上传
     */
    @Bean
    public S3Presigner s3Presigner(@Value("${rustfs.url}") String rustfsUrl,
                                   @Value("${rustfs.access-key-id}") String accessKeyId,
                                   @Value("${rustfs.secret-access-key}") String secretAccessKey) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(rustfsUrl))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                        )
                )
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
