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
 * 百炼的 OpenAI 兼容端点用于聊天（/compatible-mode/v1/chat/completions），
 * 但模型列表需要通过 DashScope 原生 API 获取：
 * GET https://dashscope.aliyuncs.com/api/v1/models
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
            // 百炼的模型列表使用 DashScope 原生 API，非 OpenAI 兼容路径
            String url = normalizeBaseUrl(baseUrl) + "/api/v1/models";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("百炼获取模型列表失败: HTTP {}", response.statusCode());
                // 兜底：尝试 OpenAI 兼容路径
                return super.fetchModels(baseUrl, apiKey);
            }

            return parseDashScopeModels(response.body());
        } catch (Exception e) {
            log.error("百炼获取模型列表异常，尝试 OpenAI 兼容路径", e);
            // 兜底
            try {
                return super.fetchModels(baseUrl, apiKey);
            } catch (Exception ex) {
                return List.of();
            }
        }
    }

    /**
     * 规范化基础 URL，移除尾部 /v1 或 /compatible-mode/v1 等路径
     */
    private String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 移除尾部路径分段
        if (url.endsWith("/compatible-mode/v1")) {
            url = url.substring(0, url.length() - "/compatible-mode/v1".length());
        } else if (url.endsWith("/v1")) {
            url = url.substring(0, url.length() - "/v1".length());
        } else if (url.endsWith("/api/v1")) {
            url = url.substring(0, url.length() - "/api/v1".length());
        }
        return url;
    }

    /**
     * 解析百炼 DashScope 原生 API 的模型列表响应
     * <p>
     * 响应格式：
     * {
     *   "request_id": "...",
     *   "data": {
     *     "model_list": [
     *       {"model_id": "qwen-plus", ...}
     *     ]
     *   }
     * }
     * </p>
     */
    private List<RemoteModelInfo> parseDashScopeModels(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null) {
                log.warn("百炼响应中没有 data 字段");
                return result;
            }

            JsonNode modelList = data.get("model_list");
            if (modelList == null || !modelList.isArray()) {
                // 尝试兼容 OpenAI 格式
                return parseModelsResponse(responseBody);
            }

            for (JsonNode node : modelList) {
                String modelId = node.has("model_id") ? node.get("model_id").asText() : "";
                if (modelId.isEmpty()) continue;

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

    @Override
    protected String normalizeUrl(String baseUrl) {
        String url = baseUrl;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 百炼的 OpenAI 兼容 API 路径为 /compatible-mode/v1
        if (!url.endsWith("/v1") && !url.endsWith("/compatible-mode/v1")) {
            url = url + "/compatible-mode/v1";
        } else if (url.endsWith("/v1") && !url.contains("/compatible-mode/")) {
            url = url.replace("/v1", "/compatible-mode/v1");
        }
        return url;
    }
}
