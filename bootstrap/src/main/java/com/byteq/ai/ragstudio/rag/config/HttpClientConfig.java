package com.byteq.ai.ragstudio.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OkHttp HTTP 客户端配置类
 * <p>
 * 仅保留同步 OkHttpClient，供百炼 Rerank 客户端（非标准协议）使用。
 * Chat 模型通信已由 AgentScope 框架接管；本客户端仅供 SKILL / MCP 等业务 HTTP 调用。
 */
@Configuration
public class HttpClientConfig {

    /**
     * 同步 HTTP 客户端
     * <p>
     * 供 BaiLianRerankClient 等仍需直接 HTTP 调用的场景使用。
     * 连接超时10秒，读写超时30秒，总超时45秒，启用连接失败重试。
     * 禁用自动重定向：由 HttpClientHelper 手动跟随并对每一跳做 SSRF 校验，
     * 防止攻击者利用 302 跳转到内网/云元数据地址绕过防护。
     */
    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }
}
