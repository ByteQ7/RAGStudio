package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * SiliconFlow（硅基流动）API 适配器
 * <p>
 * SiliconFlow 提供 OpenAI 兼容 API，但模型列表返回的数据结构中
 * 包含更丰富的类型信息（如 type: "chat" / "embedding" / "rerank"），
 * 可以更精确地映射 capability。
 * </p>
 */
@Slf4j
public class SiliconFlowAdapter extends OpenaiCompatibleAdapter {

    @Override
    public boolean supports(String providerName) {
        return "siliconflow".equalsIgnoreCase(providerName)
                || "硅基流动".equals(providerName);
    }

    @Override
    protected List<RemoteModelInfo> parseModelsResponse(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("SiliconFlow 响应中没有 data 数组");
                return result;
            }

            for (JsonNode node : data) {
                String modelId = node.get("id").asText();
                String type = node.has("type") ? node.get("type").asText() : "chat";
                String object = node.has("object") ? node.get("object").asText() : "";

                // 跳过非模型条目
                if (!"model".equals(object)) continue;

                // 根据 type 字段映射 capability
                List<String> capabilities = new ArrayList<>();
                String lowerType = type.toLowerCase();
                if (lowerType.contains("chat") || lowerType.contains("language")) {
                    capabilities.add("CHAT");
                }
                if (lowerType.contains("embedding")) {
                    capabilities.add("EMBEDDING");
                }
                if (lowerType.contains("rerank")) {
                    capabilities.add("RERANK");
                }
                // 兜底：默认 CHAT
                if (capabilities.isEmpty()) {
                    capabilities.add("CHAT");
                }

                boolean supportsThinking = modelId.toLowerCase().contains("reason")
                        || modelId.toLowerCase().contains("thinking")
                        || modelId.toLowerCase().contains("deepseek-r1");
                boolean supportsMultimodal = modelId.toLowerCase().contains("vision")
                        || modelId.toLowerCase().contains("vl");

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
            log.error("解析 SiliconFlow 模型列表响应失败", e);
        }
        return result;
    }
}
