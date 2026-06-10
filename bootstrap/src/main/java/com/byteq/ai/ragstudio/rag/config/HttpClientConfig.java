package com.byteq.ai.ragstudio.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * OkHttp HTTP 客户端配置类
 * <p>
 * 配置系统中使用的两个 OkHttpClient 实例：
 * <ul>
 *   <li><b>streamingHttpClient：</b>流式客户端，连接超时30秒，读写无超时限制，适用于 SSE 流式响应场景</li>
 *   <li><b>syncHttpClient：</b>同步客户端，连接超时10秒，读写超时30秒，总超时45秒，适用于普通 HTTP 请求</li>
 * </ul>
 * </p>
 */
@Configuration
public class HttpClientConfig {

    /**
     * 流式 HTTP 客户端（Primary）
     * <p>
     * 适用于 SSE（Server-Sent Events）流式响应场景。
     * 连接超时30秒，读写无超时限制，确保流式连接不会因空闲而被断开。
     * 启用连接失败重试机制。
     * </p>
     *
     * @return 流式 OkHttpClient 实例
     */
    @Bean
    @Primary
    public OkHttpClient streamingHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ZERO)
                .callTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 同步 HTTP 客户端
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
