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
import java.util.Map;

/**
 * OpenAI 兼容 API 适配器
 * <p>
 * 适用于绝大多数 OpenAI 兼容的 API 服务（如 DeepSeek、SiliconFlow、智谱等）。
 * 使用 JDK 内置的 HttpClient（无需额外依赖）。
 * </p>
 * <ul>
 *   <li>连通性检查: 默认调用 GET /v1/models</li>
 *   <li>模型列表: 默认调用 GET /v1/models</li>
 * </ul>
 * <p>
 * API 路径由供应商的端点配置（endpoints）控制，可在界面上自定义。
 * 未配置时使用适配器内置的默认路径。
 * </p>
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
        return true;
    }

    @Override
    public ConnectivityResult checkConnectivity(String baseUrl, String apiKey, Map<String, String> endpoints) {
        Instant start = Instant.now();
        try {
            String modelsUrl = resolveEndpointUrl(baseUrl, endpoints, "models", "/v1/models");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            if (response.statusCode() == 200) {
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
    public List<RemoteModelInfo> fetchModels(String baseUrl, String apiKey, Map<String, String> endpoints) {
        try {
            String modelsUrl = resolveEndpointUrl(baseUrl, endpoints, "models", "/v1/models");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(modelsUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("获取模型列表失败: HTTP {}, url={}", response.statusCode(), modelsUrl);
                return List.of();
            }

            return parseModelsResponse(response.body());
        } catch (Exception e) {
            log.error("获取模型列表异常", e);
            return List.of();
        }
    }

    /**
     * 解析 OpenAI 兼容的模型列表响应
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

                String object = node.has("object") ? node.get("object").asText() : "";
                if (!"model".equals(object) && !object.isEmpty()) continue;

                List<String> capabilities = new ArrayList<>();
                boolean isEmbedding = lowerId.contains("embed") || lowerId.contains("bge-") || lowerId.contains("e5-");
                if (isEmbedding) {
                    capabilities.add("EMBEDDING");
                } else if (lowerId.contains("rerank")) {
                    capabilities.add("RERANK");
                } else {
                    capabilities.add("CHAT");
                }

                List<Integer> dimensions = null;
                if (isEmbedding) {
                    if (lowerId.contains("large")) {
                        dimensions = List.of(256, 512, 1024, 1536);
                    } else if (lowerId.contains("small")) {
                        dimensions = List.of(1536);
                    } else {
                        dimensions = List.of(1536);
                    }
                }

                result.add(new RemoteModelInfo(
                        id, id, capabilities,
                        lowerId.contains("reason") || lowerId.contains("thinking"),
                        lowerId.contains("vision") || lowerId.contains("vl"),
                        dimensions
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
     * 根据端点配置解析完整 URL
     * <p>
     * 如果 endpoints 中配置了指定 key 的路径，则使用 baseUrl + 配置路径；
     * 否则使用 baseUrl + 适配器默认路径（normalizeUrl + defaultPath）。
     * </p>
     *
     * @param baseUrl     API 基础地址
     * @param endpoints   端点配置映射
     * @param key         端点 key（如 "models"、"chat"）
     * @param defaultPath 默认路径（如 "/v1/models"）
     * @return 完整 URL
     */
    protected String resolveEndpointUrl(String baseUrl, Map<String, String> endpoints, String key, String defaultPath) {
        if (endpoints != null && endpoints.containsKey(key)) {
            return joinUrl(baseUrl, endpoints.get(key));
        }
        return normalizeUrl(baseUrl) + defaultPath;
    }

    /**
     * 拼接基础 URL 和路径，自动处理斜杠分隔
     */
    protected static String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    /**
     * 规范化 URL：移除尾部 /，确保以 /v1 结尾
     */
    protected String normalizeUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        return url;
    }
}
