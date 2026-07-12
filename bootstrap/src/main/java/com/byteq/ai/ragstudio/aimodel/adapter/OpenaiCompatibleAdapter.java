package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容 API 适配器
 * <p>
 * 适用于绝大多数 OpenAI 兼容的 API 服务（如 DeepSeek、SiliconFlow、智谱等）。
 * 使用 JDK 内置的 HttpClient（无需额外依赖）。
 * </p>
 * <ul>
 *   <li>连通性检查: 调用 GET /v1/models，验证 API Key 和 Base URL 有效性</li>
 *   <li>模型列表: 调用 GET /v1/models</li>
 * </ul>
 */
@Slf4j
public class OpenaiCompatibleAdapter implements ProviderAdapter {

    protected final ObjectMapper objectMapper;
    protected final HttpClient httpClient;

    public OpenaiCompatibleAdapter() {
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean supports(String providerName) {
        // 兜底适配器：支持所有供应商
        return true;
    }

    @Override
    public ConnectivityResult checkConnectivity(String baseUrl, String apiKey) {
        Instant start = Instant.now();
        try {
            // 使用 GET /v1/models 检查连通性 — 这是 OpenAI 兼容 API 的标准健康检查端点
            // 优势：无需指定模型名、0 成本、仅验证 Key 有效性、所有供应商都支持
            String modelsUrl = normalizeUrl(baseUrl) + "/models";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            if (response.statusCode() == 200) {
                // 可选：解析响应验证返回的是有效的模型列表
                try {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode data = root.get("data");
                    if (data != null && data.isArray() && data.size() > 0) {
                        return new ConnectivityResult(true, latencyMs,
                                "可用模型 " + data.size() + " 个");
                    }
                } catch (Exception ignored) {
                }
                return new ConnectivityResult(true, latencyMs, null);
            } else {
                String errorMsg = extractError(response.body());
                return new ConnectivityResult(false, latencyMs, "HTTP " + response.statusCode() + ": " + errorMsg);
            }
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            return new ConnectivityResult(false, latencyMs, e.getMessage());
        }
    }

    @Override
    public List<RemoteModelInfo> fetchModels(String baseUrl, String apiKey) {
        try {
            String modelsUrl = normalizeUrl(baseUrl) + "/models";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("获取模型列表失败: HTTP {}", response.statusCode());
                return List.of();
            }

            return parseModelsResponse(response.body());
        } catch (Exception e) {
            log.error("获取模型列表异常", e);
            return List.of();
        }
    }

    /**
     * 解析 OpenAI 兼容的 /v1/models 响应
     * <p>
     * 标准格式: {"data": [{"id": "gpt-4", "object": "model", ...}]}
     * </p>
     */
    protected List<RemoteModelInfo> parseModelsResponse(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("响应中没有 data 数组: {}", responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody);
                return result;
            }

            for (JsonNode node : data) {
                String id = node.get("id").asText();
                String lowerId = id.toLowerCase();

                // Openai 兼容 API 的模型列表响应中可能有 object 字段，跳过非模型条目
                String object = node.has("object") ? node.get("object").asText() : "";
                if (!"model".equals(object) && !object.isEmpty()) continue;

                // 根据模型 ID 命名规则推断能力类型
                List<String> capabilities = new ArrayList<>();
                if (lowerId.contains("embed") || lowerId.contains("bge-") || lowerId.contains("e5-")) {
                    capabilities.add("EMBEDDING");
                } else if (lowerId.contains("rerank")) {
                    capabilities.add("RERANK");
                } else {
                    capabilities.add("CHAT");
                }

                result.add(new RemoteModelInfo(
                        id,
                        id,
                        capabilities,
                        lowerId.contains("reason") || lowerId.contains("thinking"),
                        lowerId.contains("vision") || lowerId.contains("vl"),
                        null
                ));
            }
        } catch (Exception e) {
            log.error("解析模型列表响应失败", e);
        }
        return result;
    }

    /**
     * 从错误响应中提取错误信息
     */
    protected String extractError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "unknown error";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.get("error");
            if (error != null) {
                if (error.has("message")) {
                    return error.get("message").asText();
                }
                return error.toString();
            }
        } catch (Exception ignored) {
        }
        return responseBody.length() > 100 ? responseBody.substring(0, 100) : responseBody;
    }

    /**
     * 规范化 URL：移除尾部 /v1 或 /，保证以 /v1 结尾
     */
    protected String normalizeUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 如果 URL 不包含 /v1 路径，加上 /v1
        // （因为大多数 OpenAI 兼容 API 是 /v1/chat/completions 或 /v1/models）
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        return url;
    }
}
