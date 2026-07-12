package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云百炼（DashScope）API 适配器
 * <p>
 * 百炼的模型列表 API: GET {base_url}/compatible-mode/v1/models
 * 需要同时传入 Authorization 和 X-Api-Key 请求头。
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
    public List<RemoteModelInfo> fetchModels(String baseUrl, String apiKey) {
        try {
            // 百炼模型列表: GET {base_url}/compatible-mode/v1/models
            String url = normalizeUrl(baseUrl) + "/models";
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Api-Key", apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15));

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("百炼获取模型列表失败: HTTP {}, body={}", response.statusCode(),
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
    public ConnectivityResult checkConnectivity(String baseUrl, String apiKey) {
        // 百炼连通性检查：调用 GET /compatible-mode/v1/models 验证 Key 有效性
        java.time.Instant start = java.time.Instant.now();
        try {
            String url = normalizeUrl(baseUrl) + "/models";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Api-Key", apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = java.time.Duration.between(start, java.time.Instant.now()).toMillis();

            if (response.statusCode() == 200) {
                return new ConnectivityResult(true, latencyMs, null);
            } else {
                return new ConnectivityResult(false, latencyMs, "HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            long latencyMs = java.time.Duration.between(start, java.time.Instant.now()).toMillis();
            return new ConnectivityResult(false, latencyMs, e.getMessage());
        }
    }

    @Override
    protected String normalizeUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/v1") && !url.endsWith("/compatible-mode/v1")) {
            url = url + "/compatible-mode/v1";
        } else if (url.endsWith("/v1") && !url.contains("/compatible-mode/")) {
            url = url.replace("/v1", "/compatible-mode/v1");
        }
        return url;
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

                result.add(new RemoteModelInfo(
                        modelId,
                        modelId,
                        capabilities,
                        supportsThinking,
                        supportsMultimodal,
                        null
                ));
            }
        } catch (Exception e) {
            log.error("解析百炼模型列表响应失败", e);
        }
        return result;
    }
}
