package com.byteq.ai.ragstudio.infra.highlight;

import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 语义高亮服务 HTTP 客户端
 * <p>
 * 调用 Python 微服务（resources/docker/semantic-highlight/）提供的语义高亮和重排序能力。
 * </p>
 *
 * <pre>
 * rag:
 *   semantic-highlight:
 *     enabled: false      # 默认关闭，开启后替换 LLM 高亮
 *     base-url: http://localhost:8001
 *     connect-timeout: 5s
 *     read-timeout: 30s
 * </pre>
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "rag.semantic-highlight")
public class SemanticHighlightClient {

    @Setter
    private boolean enabled = false;

    @Setter
    private String baseUrl = "http://localhost:8001";

    @Setter
    private Duration connectTimeout = Duration.ofSeconds(5);

    @Setter
    private Duration readTimeout = Duration.ofSeconds(30);

    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)  // 强制 HTTP/1.1，避免 h2c 升级问题
                .build();
        log.info("语义高亮客户端初始化: enabled={}, baseUrl={}", enabled, baseUrl);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ==================== 高亮 ====================

    public SemanticHighlightResponse highlight(String question,
                                               List<SemanticHighlightRequest.ChunkItem> chunks) {
        return highlight(question, chunks, 0.5);
    }

    public SemanticHighlightResponse highlight(String question,
                                               List<SemanticHighlightRequest.ChunkItem> chunks,
                                               double threshold) {
        SemanticHighlightRequest request = SemanticHighlightRequest.builder()
                .question(question)
                .chunks(chunks)
                .threshold(threshold)
                .build();
        return post("/highlight", request, SemanticHighlightResponse.class);
    }

    // ==================== 重排序 ====================

    public RerankResponse rerank(String question,
                                 List<SemanticHighlightRequest.ChunkItem> chunks) {
        RerankRequest request = RerankRequest.builder()
                .question(question)
                .chunks(chunks)
                .build();
        return post("/rerank", request, RerankResponse.class);
    }

    // ==================== 健康检查 ====================

    public boolean health() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(readTimeout)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && resp.body().contains("\"status\":\"ok\"");
        } catch (Exception e) {
            log.warn("语义高亮服务健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 内部 ====================

    /**
     * 发送 POST 请求，返回反序列化后的响应
     */
    private <T> T post(String path, Object request, Class<T> responseType) {
        String url = baseUrl + path;
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new RemoteException("语义服务请求序列化失败: " + e.getMessage());
        }
        log.info("=== 语义服务请求 URL: {} ===", url);
        log.info("=== 语义服务请求 JSON: {} ===", json);

        // 服务启动初期可能尚未就绪，最多重试 2 次 (间隔 1s, 2s)
        int maxAttempts = 2;
        IOException lastIoException = null;
        for (int attempt = 0; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(readTimeout)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() != 200) {
                    String respBody = resp.body();
                    log.warn("语义服务返回非200: code={}, body={}, request={}",
                            resp.statusCode(), truncate(respBody, 300), truncate(json, 500));
                    throw new RemoteException("语义服务返回错误: HTTP " + resp.statusCode() + " " +
                            (respBody.length() > 200 ? respBody.substring(0, 200) : respBody));
                }

                return objectMapper.readValue(resp.body(), responseType);
            } catch (RemoteException e) {
                throw e;
            } catch (IOException e) {
                lastIoException = e;
                if (attempt < maxAttempts) {
                    long delay = 1000L * (attempt + 1);
                    log.warn("语义服务连接失败(将在{}ms后重试): url={}, attempt={}/{}",
                            delay, url, attempt + 1, maxAttempts + 1);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("语义服务调用失败(已降级保留原文): url={}{}, requestClass={}",
                        baseUrl, path, request.getClass().getSimpleName(), e);
                throw new RemoteException("语义服务不可用: " + e.getMessage());
            }
        }
        throw new RemoteException("语义服务不可用(重试" + (maxAttempts + 1) + "次均失败): "
                + lastIoException.getMessage());
    }

    /** 截断长文本用于日志 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
