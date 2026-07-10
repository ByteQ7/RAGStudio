package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 火山引擎（豆包大模型）API 适配器
 * <p>
 * 火山引擎通过 OpenAI 兼容接口提供豆包系列模型服务。
 * 文档：https://www.volcengine.com/docs/82379
 * </p>
 * <p>
 * 模型列表 API: GET /v1/models
 * 需在请求头添加 `Authorization: Bearer {apiKey}`
 * </p>
 */
@Slf4j
public class VolcEngineAdapter extends OpenaiCompatibleAdapter {

    @Override
    public boolean supports(String providerName) {
        return "volcengine".equalsIgnoreCase(providerName)
                || "volcano".equalsIgnoreCase(providerName)
                || "火山引擎".equals(providerName);
    }

    @Override
    protected List<RemoteModelInfo> parseModelsResponse(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("火山引擎响应中没有 data 数组");
                return result;
            }

            for (JsonNode node : data) {
                String modelId = node.get("id").asText();
                String lowerId = modelId.toLowerCase();

                // 火山引擎的模型命名规则：
                // - doubao-* → CHAT
                // - doubao-embedding → EMBEDDING
                // - doubao-rerank → RERANK
                List<String> capabilities = new ArrayList<>();
                if (lowerId.contains("embed")) {
                    capabilities.add("EMBEDDING");
                } else if (lowerId.contains("rerank")) {
                    capabilities.add("RERANK");
                } else {
                    capabilities.add("CHAT");
                }

                boolean supportsThinking = lowerId.contains("reason")
                        || lowerId.contains("deepthink");
                boolean supportsMultimodal = lowerId.contains("vision")
                        || lowerId.contains("vl")
                        || lowerId.contains("visual");

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
            log.error("解析火山引擎模型列表响应失败", e);
        }
        return result;
    }
}
