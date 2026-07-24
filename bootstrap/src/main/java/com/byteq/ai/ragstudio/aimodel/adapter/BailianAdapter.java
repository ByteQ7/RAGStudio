package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 阿里云百炼（DashScope）API 适配器
 * <p>
 * 百炼 API 路径由端点配置控制，默认路径为 /compatible-mode/v1/…。
 * 文档：https://help.aliyun.com/product/303425.html
 * </p>
 */
@Slf4j
public class BailianAdapter extends OpenaiCompatibleAdapter {

    @Override
    public boolean supports(String providerName) {
        return "bailian".equalsIgnoreCase(providerName)
                || "百炼".equals(providerName)
                || "阿里云".equals(providerName)
                || "alibaba".equalsIgnoreCase(providerName);
    }

    @Override
    public List<RemoteModelInfo> fetchModels(String baseUrl, String apiKey, Map<String, String> endpoints) {
        try {
            String url = resolveEndpointUrl(baseUrl, endpoints, "models", "/compatible-mode/v1/models");
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Api-Key", apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15));

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("百炼获取模型列表失败: HTTP {}, url={}, body={}", response.statusCode(), url,
                        response.body() != null ? response.body().substring(0, Math.min(200, response.body().length())) : "");
                return List.of();
            }

            return parseModelsResponse(response.body());
        } catch (Exception e) {
            log.error("百炼获取模型列表异常", e);
            return List.of();
        }
    }

    @Override
    public ConnectivityResult checkConnectivity(String baseUrl, String apiKey, Map<String, String> endpoints) {
        Instant start = Instant.now();
        try {
            String url = resolveEndpointUrl(baseUrl, endpoints, "models", "/compatible-mode/v1/models");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Api-Key", apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            if (response.statusCode() == 200) {
                return new ConnectivityResult(true, latencyMs, null);
            } else {
                return new ConnectivityResult(false, latencyMs, "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            return new ConnectivityResult(false, latencyMs, e.getMessage());
        }
    }

    @Override
    protected List<RemoteModelInfo> parseModelsResponse(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("百炼响应中没有 data 数组: {}", responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody);
                return result;
            }

            for (JsonNode node : data) {
                String modelId = node.get("id").asText();
                String lowerId = modelId.toLowerCase();

                List<String> capabilities = new ArrayList<>();
                if (lowerId.contains("embed")) {
                    capabilities.add("EMBEDDING");
                } else if (lowerId.contains("rerank")) {
                    capabilities.add("RERANK");
                } else {
                    capabilities.add("CHAT");
                }

                boolean supportsThinking = lowerId.contains("deepthink")
                        || lowerId.contains("reason");
                boolean supportsMultimodal = lowerId.contains("vl")
                        || lowerId.contains("vision");

                List<Integer> dimensions = null;
                if (capabilities.contains("EMBEDDING")) {
                    dimensions = List.of(1536);
                }

                result.add(new RemoteModelInfo(
                        modelId, modelId, capabilities,
                        supportsThinking, supportsMultimodal, dimensions
                ));
            }
        } catch (Exception e) {
            log.error("解析百炼模型列表响应失败", e);
        }
        return result;
    }
}
