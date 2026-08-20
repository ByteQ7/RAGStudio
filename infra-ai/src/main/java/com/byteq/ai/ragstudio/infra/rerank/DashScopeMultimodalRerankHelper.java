package com.byteq.ai.ragstudio.infra.rerank;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.infra.http.HttpResponseHelper;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.http.ModelUrlResolver;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DashScope 多模态 Rerank HTTP 实现
 * <p>
 * 官方 SDK 的 {@code TextReRank} 仅支持纯文本文档，多模态 rerank（如 qwen3-vl-rerank
 * 图文混合）走 DashScope 原生 HTTP 接口：
 * {@code POST /api/v1/services/rerank/text-rerank/text-rerank}。
 * 该实现由 {@code DashScopeGateway} 在多模态场景下调用。
 * </p>
 */
@Slf4j
@Component
public class DashScopeMultimodalRerankHelper {

    private static final String PROVIDER = "bailian";

    private final OkHttpClient httpClient;
    private final ProtocolRegistry protocolRegistry;

    public DashScopeMultimodalRerankHelper(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                                           ProtocolRegistry protocolRegistry) {
        this.httpClient = httpClient;
        this.protocolRegistry = protocolRegistry;
    }

    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk rc : candidates) {
            if (seen.add(rc.getId())) {
                dedup.add(rc);
            }
        }
        return doRerank(query, dedup, topN, target);
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        DynamicModelConfig.ProviderEntry provider = HttpResponseHelper.requireProvider(target, PROVIDER);
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());

        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", HttpResponseHelper.requireModel(target, PROVIDER));

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        boolean multimodal = Boolean.TRUE.equals(
                target.candidate() != null && target.candidate().getSupportsMultimodal());

        List<RetrievedChunk> docCandidates = new ArrayList<>();
        JsonArray documentsArray = new JsonArray();
        for (RetrievedChunk each : candidates) {
            if (each.isImage()) {
                if (!multimodal) {
                    continue;
                }
                String imageUrl = extractRerankImageUrl(each);
                if (imageUrl == null) {
                    log.warn("rerank 跳过无有效图片地址的 IMAGE chunk: id={}", each.getId());
                    continue;
                }
                JsonObject imageDoc = new JsonObject();
                imageDoc.addProperty("image", imageUrl);
                documentsArray.add(imageDoc);
            } else {
                String text = each.getText() == null ? "" : each.getText();
                if (text.isBlank()) {
                    log.warn("rerank 跳过空文本 chunk: id={}", each.getId());
                    continue;
                }
                if (multimodal) {
                    JsonObject textDoc = new JsonObject();
                    textDoc.addProperty("text", text);
                    documentsArray.add(textDoc);
                } else {
                    documentsArray.add(text);
                }
            }
            docCandidates.add(each);
        }
        input.add("documents", documentsArray);

        if (docCandidates.isEmpty()) {
            return candidates;
        }

        JsonObject parameters = new JsonObject();
        parameters.addProperty("top_n", topN);
        parameters.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", parameters);

        String url;
        try {
            url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK);
        } catch (IllegalStateException e) {
            url = provider.getUrl().replaceAll("/+$", "") + protocol.resolveRerankUrlFallback();
        }
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(reqBody.toString(), okhttp3.MediaType.get("application/json; charset=utf-8")))
                .addHeader(protocol.authHeaderName(), protocol.authHeaderValue(provider.getApiKey()))
                .build();

        JsonObject respJson;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = HttpResponseHelper.readBody(response.body());
                log.warn("{} rerank 请求失败: status={}, body={}", PROVIDER, response.code(), body);
                throw new ModelClientException(
                        PROVIDER + " rerank 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), PROVIDER);
        } catch (IOException e) {
            throw new ModelClientException(PROVIDER + " rerank 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        JsonObject output = requireOutput(respJson);
        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            throw new ModelClientException(PROVIDER + " rerank results 为空",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            if (!item.has("index")) {
                continue;
            }
            int idx = item.get("index").getAsInt();
            if (idx < 0 || idx >= docCandidates.size()) {
                continue;
            }
            RetrievedChunk src = docCandidates.get(idx);
            Float score = null;
            if (item.has("relevance_score") && !item.get("relevance_score").isJsonNull()) {
                score = item.get("relevance_score").getAsFloat();
            }
            RetrievedChunk hit = score != null
                    ? RetrievedChunk.builder()
                            .id(src.getId()).text(src.getText()).score(score)
                            .contentType(src.getContentType()).metadata(src.getMetadata())
                            .kbName(src.getKbName()).docName(src.getDocName())
                            .build()
                    : src;
            reranked.add(hit);
            addedIds.add(src.getId());
            if (reranked.size() >= topN) {
                break;
            }
        }

        if (reranked.size() < topN) {
            for (RetrievedChunk c : candidates) {
                if (addedIds.add(c.getId())) {
                    reranked.add(c);
                }
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }

        return reranked;
    }

    private JsonObject requireOutput(JsonObject respJson) {
        if (respJson == null || !respJson.has("output")) {
            throw new ModelClientException(PROVIDER + " rerank 响应缺少 output",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            throw new ModelClientException(PROVIDER + " rerank 响应缺少 results",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return output;
    }

    private String extractRerankImageUrl(RetrievedChunk chunk) {
        if (chunk.getMetadata() == null) {
            return null;
        }
        Object rerankUrl = chunk.getMetadata().get("rerank_image_url");
        if (rerankUrl instanceof String s && isFetchableImageUrl(s)) {
            return s;
        }
        Object url = chunk.getMetadata().get("image_url");
        if (url instanceof String s && isFetchableImageUrl(s)) {
            return s;
        }
        return null;
    }

    private boolean isFetchableImageUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:"));
    }
}