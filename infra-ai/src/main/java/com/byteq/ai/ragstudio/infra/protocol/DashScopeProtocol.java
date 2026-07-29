package com.byteq.ai.ragstudio.infra.protocol;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DashScopeProtocol implements ModelProtocol {

    @Override
    public String name() {
        return "dashscope";
    }

    // ---- Chat ----

    @Override
    public Map<String, Object> buildChatRequest(String modelId, List<ChatMessage> messages, boolean stream,
                                                 Double temperature, Double topP, Integer maxTokens,
                                                 Map<String, Object> reasoningParams, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("stream", stream);
        if (temperature != null) body.put("temperature", temperature);
        if (topP != null) body.put("top_p", topP);
        if (maxTokens != null && maxTokens > 0) body.put("max_tokens", maxTokens);
        if (reasoningParams != null && !reasoningParams.isEmpty()) body.putAll(reasoningParams);
        if (tools != null && !tools.isEmpty()) body.put("tools", tools);

        List<Map<String, Object>> msgs = new ArrayList<>();
        for (ChatMessage msg : messages) {
            Map<String, Object> m = new LinkedHashMap<>();
            String role = msg.getRole() != null
                    ? (msg.getRole() == ChatMessage.Role.OBSERVATION ? "system" : msg.getRole().name().toLowerCase())
                    : "user";
            m.put("role", role);
            List<String> imageUrls = msg.getImageUrls();
            if (imageUrls != null && !imageUrls.isEmpty() && msg.getRole() == ChatMessage.Role.OBSERVATION) {
                // OBSERVATION 映射为 system，但多数模型不支持 system 消息带图片。
                // 拆分为：system 文本 + user 图片消息
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                msgs.add(m);
                Map<String, Object> imgMsg = new LinkedHashMap<>();
                imgMsg.put("role", "user");
                List<Map<String, Object>> imgContent = new ArrayList<>();
                imgContent.add(Map.of("type", "text", "text", "检索到的相关图片："));
                for (String url : imageUrls) {
                    imgContent.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
                }
                imgMsg.put("content", imgContent);
                msgs.add(imgMsg);
            } else if (imageUrls != null && !imageUrls.isEmpty()) {
                List<Map<String, Object>> contentArray = new ArrayList<>();
                contentArray.add(Map.of("type", "text", "text",
                        msg.getContent() != null ? msg.getContent() : ""));
                for (String url : imageUrls) {
                    contentArray.add(Map.of("type", "image_url",
                            "image_url", Map.of("url", url)));
                }
                m.put("content", contentArray);
                msgs.add(m);
            } else {
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                msgs.add(m);
            }
        }
        body.put("messages", msgs);
        return body;
    }

    // ---- Embedding ----

    @Override
    public String resolveEmbeddingUrlFallback() {
        return "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
    }

    @Override
    public String resolveEmbeddingUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "") + "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";
    }

    @Override
    public Map<String, Object> buildEmbeddingRequest(String modelId, List<String> texts, Integer dimension) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (String text : texts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("text", text);
            contents.add(item);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("contents", contents);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("input", input);
        if (dimension != null && dimension > 0) {
            body.put("parameters", Map.of("dimension", dimension));
        }
        return body;
    }

    @Override
    public Map<String, Object> buildImageEmbeddingRequest(String modelId, List<String> imageBase64List, Integer dimension) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (String imageBase64 : imageBase64List) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("image", imageBase64);
            contents.add(item);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("contents", contents);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("input", input);
        if (dimension != null && dimension > 0) {
            body.put("parameters", Map.of("dimension", dimension));
        }
        return body;
    }

    @Override
    public List<List<Float>> extractEmbeddings(com.fasterxml.jackson.databind.JsonNode response) {
        com.fasterxml.jackson.databind.JsonNode output = response.path("output");
        if (output.isMissingNode()) {
            return ModelProtocol.super.extractEmbeddings(response);
        }
        com.fasterxml.jackson.databind.JsonNode embeddings = output.path("embeddings");
        List<List<Float>> results = new ArrayList<>();
        if (embeddings.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : embeddings) {
                com.fasterxml.jackson.databind.JsonNode emb = item.path("embedding");
                List<Float> vec = new ArrayList<>();
                if (emb.isArray()) for (com.fasterxml.jackson.databind.JsonNode v : emb) vec.add((float) v.asDouble());
                results.add(vec);
            }
        }
        return results;
    }

    // ---- Rerank ----

    @Override
    public String resolveRerankUrlFallback() {
        return "/api/v1/services/rerank/text-rerank/text-rerank";
    }

    @Override
    public List<Float> extractRerankScores(com.fasterxml.jackson.databind.JsonNode response) {
        com.fasterxml.jackson.databind.JsonNode output = response.path("output");
        if (output.isMissingNode()) {
            return ModelProtocol.super.extractRerankScores(response);
        }
        com.fasterxml.jackson.databind.JsonNode results = output.path("results");
        List<Float> scores = new ArrayList<>();
        if (results.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : results) {
                scores.add((float) item.path("relevance_score").asDouble());
            }
        }
        return scores;
    }
}
