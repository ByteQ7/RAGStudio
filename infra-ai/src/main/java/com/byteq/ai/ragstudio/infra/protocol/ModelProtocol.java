package com.byteq.ai.ragstudio.infra.protocol;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface ModelProtocol {

    String name();

    // ---- Auth ----

    default String authHeaderName() {
        return "Authorization";
    }

    default String authHeaderValue(String apiKey) {
        return "Bearer " + apiKey;
    }

    // ---- Chat ----

    /** 当 endpoints 未配置 chat 路径时，兜底的 URL 路径 */
    default String resolveChatUrlFallback() {
        return "/v1/chat/completions";
    }

    default String resolveChatUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
    }

    Map<String, Object> buildChatRequest(String modelId, List<ChatMessage> messages, boolean stream,
                                          Double temperature, Double topP, Integer maxTokens,
                                          Map<String, Object> reasoningParams, List<Map<String, Object>> tools);

    default String extractChatContent(JsonNode response) {
        JsonNode msg = response.path("choices").path(0).path("message");
        if (msg.has("content") && !msg.get("content").isNull()) {
            return msg.get("content").asText();
        }
        return "";
    }

    default void parseStreamChunk(JsonNode event, StreamCallback callback) {
        JsonNode choices = event.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode delta = choices.get(0).path("delta");
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                callback.onThinking(delta.get("reasoning_content").asText());
            }
            if (delta.has("content") && !delta.get("content").isNull()) {
                callback.onContent(delta.get("content").asText());
            }
        }
    }

    // ---- Embedding ----

    /** 当 endpoints 未配置 embedding 路径时，兜底的 URL 路径 */
    default String resolveEmbeddingUrlFallback() {
        return "/v1/embeddings";
    }

    default String resolveEmbeddingUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "") + "/v1/embeddings";
    }

    default Map<String, Object> buildEmbeddingRequest(String modelId, List<String> texts, Integer dimension) {
        throw new UnsupportedOperationException("Protocol " + name() + " does not support embedding");
    }

    default Map<String, Object> buildImageEmbeddingRequest(String modelId, List<String> imageBase64List, Integer dimension) {
        throw new UnsupportedOperationException("Protocol " + name() + " does not support image embedding");
    }

    default List<List<Float>> extractEmbeddings(JsonNode response) {
        JsonNode data = response.path("data");
        List<List<Float>> results = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                JsonNode emb = item.path("embedding");
                List<Float> vec = new ArrayList<>();
                if (emb.isArray()) for (JsonNode v : emb) vec.add((float) v.asDouble());
                results.add(vec);
            }
        }
        return results;
    }

    // ---- Rerank ----

    /** 当 endpoints 未配置 rerank 路径时，兜底的 URL 路径 */
    default String resolveRerankUrlFallback() {
        return "/v1/rerank";
    }

    default String resolveRerankUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "") + "/v1/rerank";
    }

    default Map<String, Object> buildRerankRequest(String modelId, String query, List<String> documents) {
        throw new UnsupportedOperationException("Protocol " + name() + " does not support rerank");
    }

    default List<Float> extractRerankScores(JsonNode response) {
        throw new UnsupportedOperationException("Protocol " + name() + " does not support rerank");
    }
}
