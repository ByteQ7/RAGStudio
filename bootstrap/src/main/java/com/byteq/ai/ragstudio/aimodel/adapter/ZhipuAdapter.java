package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 智谱 AI（GLM 系列模型）API 适配器
 * <p>
 * 智谱 AI 提供 OpenAI 兼容接口，但模型列表 API 路径和响应格式可能有差异。
 * 智谱的 OpenAI 兼容地址：https://open.bigmodel.cn/api/paas/v4/
 * 模型列表: GET /api/paas/v4/models
 * 或通过 OpenAI 兼容方式: GET /v1/models
 * </p>
 * <p>
 * 智谱模型命名：
 * - glm-4-plus, glm-4-air → CHAT
 * - glm-4v → 多模态
 * - embedding-* → EMBEDDING
 * </p>
 */
@Slf4j
public class ZhipuAdapter extends OpenaiCompatibleAdapter {

    @Override
    public boolean supports(String providerName) {
        return "zhipu".equalsIgnoreCase(providerName)
                || "zhipuai".equalsIgnoreCase(providerName)
                || "智谱".equals(providerName)
                || "智谱AI".equals(providerName)
                || "bigmodel".equalsIgnoreCase(providerName);
    }

    @Override
    protected List<RemoteModelInfo> parseModelsResponse(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("智谱 AI 响应中没有 data 数组");
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

                boolean supportsThinking = lowerId.contains("think");
                boolean supportsMultimodal = lowerId.contains("v-")
                        || lowerId.contains("vision")
                        || lowerId.contains("glm-4v");

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
            log.error("解析智谱 AI 模型列表响应失败", e);
        }
        return result;
    }
}
