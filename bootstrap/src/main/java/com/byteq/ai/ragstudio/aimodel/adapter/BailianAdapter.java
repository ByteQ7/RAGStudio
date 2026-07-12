package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云百炼（DashScope）API 适配器
 * <p>
 * 阿里云百炼的 OpenAI 兼容端点路径为 /compatible-mode/v1/...，
 * 与标准的 OpenAI /v1/... 路径不同。模型列表 API 为：
 * GET https://dashscope.aliyuncs.com/compatible-mode/v1/models
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

    @Override
    protected List<RemoteModelInfo> parseModelsResponse(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("百炼响应中没有 data 数组");
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
