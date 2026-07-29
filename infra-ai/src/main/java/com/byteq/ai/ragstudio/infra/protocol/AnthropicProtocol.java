package com.byteq.ai.ragstudio.infra.protocol;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnthropicProtocol implements ModelProtocol {

    @Override
    public String name() {
        return "anthropic";
    }

    // ---- Auth ----

    @Override
    public String authHeaderName() {
        return "x-api-key";
    }

    @Override
    public String authHeaderValue(String apiKey) {
        return apiKey;
    }

    // ---- Chat ----

    @Override
    public String resolveChatUrlFallback() {
        return "/v1/messages";
    }

    @Override
    public String resolveChatUrl(String baseUrl) {
        return baseUrl.replaceAll("/+$", "") + "/v1/messages";
    }

    @Override
    public Map<String, Object> buildChatRequest(String modelId, List<ChatMessage> messages, boolean stream,
                                                 Double temperature, Double topP, Integer maxTokens,
                                                 Map<String, Object> reasoningParams, List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("stream", stream);
        if (maxTokens != null && maxTokens > 0) body.put("max_tokens", maxTokens);
        if (temperature != null) body.put("temperature", temperature);
        if (topP != null) body.put("top_p", topP);

        List<Map<String, Object>> systemMessages = new ArrayList<>();
        List<Map<String, Object>> userAssistantMessages = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if (msg.getRole() == ChatMessage.Role.SYSTEM) {
                Map<String, Object> textBlock = new LinkedHashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", msg.getContent() != null ? msg.getContent() : "");
                systemMessages.add(textBlock);
                continue;
            }
            String role = msg.getRole() == ChatMessage.Role.ASSISTANT ? "assistant" : "user";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", role);
            List<Map<String, Object>> contentBlocks = new ArrayList<>();

            if (msg.getContent() != null && !msg.getContent().isBlank()) {
                Map<String, Object> textBlock = new LinkedHashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", msg.getContent());
                contentBlocks.add(textBlock);
            }

            List<String> imageUrls = msg.getImageUrls();
            if (imageUrls != null && !imageUrls.isEmpty()) {
                for (String url : imageUrls) {
                    Map<String, Object> imgBlock = buildImageBlock(url);
                    if (imgBlock != null) {
                        contentBlocks.add(imgBlock);
                    }
                }
            }

            if (!contentBlocks.isEmpty()) {
                m.put("content", contentBlocks);
            }
            userAssistantMessages.add(m);
        }

        if (!systemMessages.isEmpty()) {
            body.put("system", systemMessages);
        }
        body.put("messages", userAssistantMessages);
        return body;
    }

    private Map<String, Object> buildImageBlock(String url) {
        if (url == null || url.isBlank()) return null;
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", detectMediaType(url));
        source.put("data", extractBase64Data(url));

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        return block;
    }

    private String detectMediaType(String url) {
        if (url.contains("image/png") || url.contains(".png")) return "image/png";
        if (url.contains("image/gif") || url.contains(".gif")) return "image/gif";
        if (url.contains("image/webp") || url.contains(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String extractBase64Data(String dataUri) {
        if (dataUri == null) return "";
        if (dataUri.contains(";base64,")) {
            return dataUri.substring(dataUri.indexOf(";base64,") + 8);
        }
        return dataUri;
    }

    @Override
    public String extractChatContent(JsonNode response) {
        JsonNode content = response.path("content");
        if (content.isArray() && content.size() > 0) {
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    return block.path("text").asText();
                }
            }
        }
        return "";
    }

    @Override
    public void parseStreamChunk(JsonNode event, StreamCallback callback) {
        String type = event.path("type").asText();
        if ("content_block_delta".equals(type)) {
            JsonNode delta = event.path("delta");
            if ("text_delta".equals(delta.path("type").asText())) {
                callback.onContent(delta.path("text").asText());
            }
        }
    }
}
