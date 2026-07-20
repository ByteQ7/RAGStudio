package com.byteq.ai.ragstudio.infra.highlight;

import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
        StringBuilder json = new StringBuilder();
        json.append("{\"question\":").append(toJsonString(question));
        json.append(",\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) json.append(",");
            SemanticHighlightRequest.ChunkItem c = chunks.get(i);
            json.append("{\"id\":").append(toJsonString(c.getId()))
                    .append(",\"text\":").append(toJsonString(c.getText()))
                    .append("}");
        }
        json.append("],\"threshold\":").append(threshold).append("}");

        return postRaw("/highlight", json.toString(), SemanticHighlightResponse.class);
    }

    // ==================== 重排序 ====================

    public RerankResponse rerank(String question,
                                 List<SemanticHighlightRequest.ChunkItem> chunks) {
        StringBuilder json = new StringBuilder();
        json.append("{\"question\":").append(toJsonString(question));
        json.append(",\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) json.append(",");
            SemanticHighlightRequest.ChunkItem c = chunks.get(i);
            json.append("{\"id\":").append(toJsonString(c.getId()))
                    .append(",\"text\":").append(toJsonString(c.getText()))
                    .append("}");
        }
        json.append("]}");

        return postRaw("/rerank", json.toString(), RerankResponse.class);
    }

    /**
     * 将字符串转为 JSON 字符串（转义特殊字符）
     */
    private static String toJsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 发送 POST 请求（原始 JSON 字符串）
     */
    private <T> T postRaw(String path, String json, Class<T> responseType) {
        try {
            String url = baseUrl + path;
            log.debug("语义服务请求: url={}, body_len={}", url, json.length());

            // 使用字节数组确保 UTF-8 编码
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            byte[] bodyBytes = json.getBytes(utf8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(readTimeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                String respBody = resp.body();
                log.warn("语义服务返回非200: code={}, body={}", resp.statusCode(), truncate(respBody, 300));
                throw new RemoteException("语义服务返回错误: HTTP " + resp.statusCode() + " " +
                        (respBody.length() > 200 ? respBody.substring(0, 200) : respBody));
            }

            return objectMapper.readValue(resp.body(), responseType);
        } catch (RemoteException e) {
            throw e;
        } catch (Exception e) {
            log.error("语义服务调用失败: url={}{}", baseUrl, path, e);
            throw new RemoteException("语义服务不可用: " + e.getMessage());
        }
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
        try {
            String url = baseUrl + path;
            String json = objectMapper.writeValueAsString(request);
            log.info("=== 语义服务请求 URL: {} ===", url);
            log.info("=== 语义服务请求 JSON: {} ===", json);

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
        } catch (Exception e) {
            log.error("语义服务调用失败: url={}{}, requestClass={}",
                    baseUrl, path, request.getClass().getSimpleName(), e);
            throw new RemoteException("语义服务不可用: " + e.getMessage());
        }
    }

    /** 截断长文本用于日志 */
    private static String truncate(String text, int maxLen) {
        if (text == null) return "null";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
