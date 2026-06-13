package com.byteq.ai.ragstudio.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OkHttp HTTP 客户端配置类
 * <p>
 * 仅保留同步 OkHttpClient，供百炼 Rerank 客户端（非标准协议）使用。
 * Chat / Embedding 的 HTTP 通信已由 Spring AI 框架接管，不再需要手工管理的 OkHttp 客户端。
 */
@Configuration
public class HttpClientConfig {

    /**
     * 同步 HTTP 客户端
     * <p>
     * 供 BaiLianRerankClient 等仍需直接 HTTP 调用的场景使用。
     * 连接超时10秒，读写超时30秒，总超时45秒，启用连接失败重试。
     */
    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true)
                .build();
    }
}
