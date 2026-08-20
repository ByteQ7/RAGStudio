package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelHttpClient;
import com.byteq.ai.ragstudio.infra.http.ModelUrlResolver;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用 OpenAI / Anthropic 兼容协议网关（手写 HTTP 层兜底）
 * <p>
 * 复用现有 {@link ModelProtocol} + {@link ModelHttpClient} 实现，保持旧行为不变，
 * 作为无官方 SDK / openai-java / anthropic-java 无法覆盖的极端厂商的最后兜底。
 * 不主动匹配（supports 恒为 false），由 {@link ProviderGatewayRegistry} 在无其他网关命中时回落。
 * </p>
 */
@Slf4j
@Component
@Order(Integer.MAX_VALUE)
public class OpenAiCompatibleGateway implements ProviderGateway {

    private final HttpModelFactory modelFactory;
    private final ModelHttpClient httpClient;
    private final ProtocolRegistry protocolRegistry;

    public OpenAiCompatibleGateway(HttpModelFactory modelFactory, ModelHttpClient httpClient,
                                   ProtocolRegistry protocolRegistry) {
        this.modelFactory = modelFactory;
        this.httpClient = httpClient;
        this.protocolRegistry = protocolRegistry;
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public boolean supports(String providerName, String protocolName) {
        // 兜底网关：不主动命中
        return false;
    }

    // ==================== Chat ====================

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String model = SdkGatewaySupport.requireModelName(target);
        List<ChatMessage> messages = SdkGatewaySupport.normalizeMessages(request);
        Map<String, Object> body = protocol.buildChatRequest(
                model, messages, false,
                request.getTemperature(), request.getTopP(), request.getMaxTokens(),
                modelFactory.resolveReasoningParams(request, target),
                request.getTools(),
                request.getResponseFormat());
        String url = modelFactory.resolveFullUrl(target, "chat", protocol.resolveChatUrlFallback());
        return httpClient.syncPost(url, target, body, protocol::extractChatContent);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String model = SdkGatewaySupport.requireModelName(target);
        List<ChatMessage> messages = SdkGatewaySupport.normalizeMessages(request);
        Map<String, Object> body = protocol.buildChatRequest(
                model, messages, true,
                request.getTemperature(), request.getTopP(), request.getMaxTokens(),
                modelFactory.resolveReasoningParams(request, target),
                request.getTools(),
                request.getResponseFormat());
        String url = modelFactory.resolveFullUrl(target, "chat", protocol.resolveChatUrlFallback());
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        return httpClient.streamPost(url, target, body, callback, thinkingLevel, null);
    }

    // ==================== Embedding ====================

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String model = SdkGatewaySupport.requireModelName(target);
        Integer dim = target.candidate() != null ? target.candidate().getDimension() : null;
        Map<String, Object> body = protocol.buildEmbeddingRequest(model, texts, dim);
        String url = modelFactory.resolveEmbeddingUrl(target);
        return httpClient.syncPost(url, target, body, protocol::extractEmbeddings);
    }

    @Override
    public List<List<Float>> embedImages(List<String> imageBase64List, ModelTarget target) {
        if (imageBase64List == null || imageBase64List.isEmpty()) {
            return List.of();
        }
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String model = SdkGatewaySupport.requireModelName(target);
        Integer dim = target.candidate() != null ? target.candidate().getDimension() : null;
        Map<String, Object> body = protocol.buildImageEmbeddingRequest(model, imageBase64List, dim);
        String url = modelFactory.resolveEmbeddingUrl(target);
        return httpClient.syncPost(url, target, body, protocol::extractEmbeddings);
    }

    // ==================== Rerank ====================

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> dedup = dedup(candidates);
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String model = SdkGatewaySupport.requireModelName(target);
        List<String> documents = new ArrayList<>();
        List<RetrievedChunk> docCandidates = new ArrayList<>();
        for (RetrievedChunk chunk : dedup) {
            if (chunk.isImage()) {
                continue;
            }
            String text = chunk.getText() == null ? "" : chunk.getText();
            if (text.isBlank()) {
                continue;
            }
            documents.add(text);
            docCandidates.add(chunk);
        }
        if (docCandidates.isEmpty()) {
            return candidates;
        }
        Map<String, Object> body = protocol.buildRerankRequest(model, query, documents);
        String url;
        try {
            url = ModelUrlResolver.resolveUrl(target.provider(), target.candidate(),
                    com.byteq.ai.ragstudio.infra.enums.ModelCapability.RERANK);
        } catch (IllegalStateException e) {
            url = SdkGatewaySupport.resolveBaseUrl(target) + protocol.resolveRerankUrlFallback();
        }
        JsonNode resp = httpClient.syncPost(url, target, body, root -> root);
        JsonNode results = resp.path("output").path("results");
        List<RetrievedChunk> reranked = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode item : results) {
                if (item.has("relevance_score")) {
                    scores.add((float) item.get("relevance_score").asDouble());
                }
            }
        }
        for (int i = 0; i < scores.size() && i < docCandidates.size() && i < topN; i++) {
            RetrievedChunk src = docCandidates.get(i);
            reranked.add(RetrievedChunk.builder()
                    .id(src.getId()).text(src.getText()).score(scores.get(i))
                    .contentType(src.getContentType()).metadata(src.getMetadata())
                    .kbName(src.getKbName()).docName(src.getDocName())
                    .build());
        }
        if (reranked.size() < topN) {
            for (RetrievedChunk c : dedup) {
                if (reranked.size() >= topN) {
                    break;
                }
                reranked.add(c);
            }
        }
        return reranked;
    }

    private List<RetrievedChunk> dedup(List<RetrievedChunk> candidates) {
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }
        return dedup;
    }
}