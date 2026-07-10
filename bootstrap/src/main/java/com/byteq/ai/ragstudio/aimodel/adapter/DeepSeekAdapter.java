package com.byteq.ai.ragstudio.aimodel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek API 适配器
 * <p>
 * DeepSeek 使用 OpenAI 兼容 API，但模型列表通过 GET /v1/models 获取，
 * 响应格式为标准 OpenAI 兼容格式。
 * 其模型主要包括 deepseek-chat（对话）和 deepseek-reasoner（推理）。
 * 还有 deepseek-vl 等视觉模型（多模态）。
 * </p>
 * <p>
 * 注意：DeepSeek 的连通性检查/chat/completions 响应中如果传空消息
 * 可能会报错或收费，所以使用轻量查询。
 * </p>
 */
@Slf4j
public class DeepSeekAdapter extends OpenaiCompatibleAdapter {

    @Override
    public boolean supports(String providerName) {
        return "deepseek".equalsIgnoreCase(providerName);
    }

    @Override
    protected List<RemoteModelInfo> parseModelsResponse(String responseBody) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) {
                log.warn("DeepSeek 响应中没有 data 数组");
                return result;
            }

            for (JsonNode node : data) {
                String modelId = node.get("id").asText();

                // 根据模型 ID 判断能力类型
                List<String> capabilities = new ArrayList<>();
                String lowerId = modelId.toLowerCase();
                if (lowerId.contains("embed") || lowerId.contains("embedding")) {
                    capabilities.add("EMBEDDING");
                } else if (lowerId.contains("rerank")) {
                    capabilities.add("RERANK");
                } else {
                    // DeepSeek 主要是 CHAT 模型
                    capabilities.add("CHAT");
                }

                boolean supportsThinking = lowerId.contains("reason")
                        || lowerId.contains("think");
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
            log.error("解析 DeepSeek 模型列表响应失败", e);
        }
        return result;
    }
}
