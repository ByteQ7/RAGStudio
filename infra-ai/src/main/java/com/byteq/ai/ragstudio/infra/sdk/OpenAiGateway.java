package com.byteq.ai.ragstudio.infra.sdk;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.Embedding;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.http.ModelHttpClient;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 官方 SDK 网关（openai-java）
 * <p>
 * 作为「OpenAI 兼容协议」的通用策略：通过自定义 {@code baseUrl} 可承载
 * DeepSeek / SiliconFlow / Moonshot / 智谱 OpenAI 兼容接口 等所有 OpenAI 兼容厂商。
 * 覆盖 chat（同步/流式）与 embedding；rerank 走 OpenAI 兼容 {@code /v1/rerank} 端点
 * （openai-java 未提供 rerank API，用同协议 HTTP 调用补齐）。
 * </p>
 */
@Slf4j
@Component
@Order(40)
public class OpenAiGateway implements ProviderGateway {

    private static final long STARTUP_TIMEOUT_MS = 45_000;
    private static final String DEFAULT_RERANK_PATH = "/v1/rerank";

    private final HttpModelFactory httpModelFactory;
    private final ModelHttpClient httpClient;

    public OpenAiGateway(HttpModelFactory httpModelFactory, ModelHttpClient httpClient) {
        this.httpModelFactory = httpModelFactory;
        this.httpClient = httpClient;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean supports(String providerName, String protocolName) {
        // 兜底承载所有 OpenAI 兼容协议；专属 SDK 网关（DashScope/Zhipu/VolcEngine）优先级更高，
        // 已在 Registry 排序中先命中，此处只接收其余厂商
        return "openai".equalsIgnoreCase(protocolName);
    }

    // ==================== Chat ====================

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        OpenAIClient client = buildClient(target);
        try {
            ChatCompletionCreateParams params = buildParams(request, target, false);
            ChatCompletion completion = client.chat().completions().create(params);
            return extractContent(completion);
        } finally {
            client.close();
        }
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        OpenAIClient client = buildClient(target);
        ChatCompletionCreateParams params = buildParams(request, target, true);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean firstReceived = new AtomicBoolean(false);
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);
        AtomicReference<StreamResponse<ChatCompletionChunk>> streamRef = new AtomicReference<>();

        try {
            StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params);
            streamRef.set(stream);
            // 用独立线程消费流，避免阻塞调用线程（与同步等待首包语义兼容）
            Thread consumer = new Thread(() -> {
                try (StreamResponse<ChatCompletionChunk> s = stream) {
                    var it = s.stream().iterator();
                    while (it.hasNext()) {
                        ChatCompletionChunk chunk = it.next();
                        if (finished.get()) {
                            break;
                        }
                        if (firstReceived.compareAndSet(false, true)) {
                            firstChunk.countDown();
                        }
                        forwardChunk(chunk, callback);
                    }
                    if (firstReceived.compareAndSet(false, true)) {
                        firstChunk.countDown();
                    }
                    if (finished.compareAndSet(false, true)) {
                        callback.onComplete();
                    }
                } catch (Throwable e) {
                    if (firstReceived.compareAndSet(false, true)) {
                        startupError.set(e);
                        firstChunk.countDown();
                    } else if (finished.compareAndSet(false, true)) {
                        callback.onError(e);
                    }
                }
            }, "openai-sse-consumer");
            consumer.setDaemon(true);
            consumer.start();

            try {
                if (!firstChunk.await(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    stream.close();
                    throw new ModelClientException("流式请求启动超时", ModelClientErrorType.NETWORK_ERROR, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stream.close();
                throw new ModelClientException("流式请求启动被中断", ModelClientErrorType.NETWORK_ERROR, null, e);
            }
            Throwable startupFailure = startupError.get();
            if (startupFailure != null) {
                stream.close();
                throw SdkGatewaySupport.translateError(provider(), startupFailure);
            }

            return () -> {
                if (finished.compareAndSet(false, true)) {
                    try {
                        streamRef.get().close();
                    } catch (Exception ignored) {
                    }
                }
            };
        } catch (ModelClientException e) {
            client.close();
            throw e;
        } catch (Exception e) {
            client.close();
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    // ==================== Embedding ====================

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        OpenAIClient client = buildClient(target);
        try {
            Integer dim = target.candidate() != null ? target.candidate().getDimension() : null;
            boolean withDimensions = dim != null && dim > 0;
            EmbeddingCreateParams.Builder pb = EmbeddingCreateParams.builder()
                    .model(SdkGatewaySupport.requireModelName(target))
                    .inputOfArrayOfStrings(List.copyOf(texts));
            if (withDimensions) {
                pb.dimensions((long) dim);
            }
            try {
                CreateEmbeddingResponse response = client.embeddings().create(pb.build());
                return extractEmbeddings(response, texts.size());
            } catch (Exception e) {
                // 部分 OpenAI 兼容厂商（如硅基流动 bge 系列）不支持 dimensions 参数，
                // 带 dimensions 请求会返回 400/422；去掉后按模型默认维度重试一次
                if (!withDimensions || !SdkGatewaySupport.isParamError(e)) {
                    throw SdkGatewaySupport.translateError(provider(), e);
                }
                log.warn("embedding 请求携带 dimensions={} 被拒，去掉 dimensions 重试: {}", dim, e.getMessage());
                EmbeddingCreateParams retry = EmbeddingCreateParams.builder()
                        .model(SdkGatewaySupport.requireModelName(target))
                        .inputOfArrayOfStrings(List.copyOf(texts))
                        .build();
                try {
                    CreateEmbeddingResponse response = client.embeddings().create(retry);
                    List<List<Float>> vectors = extractEmbeddings(response, texts.size());
                    // 降级后服务端按模型默认维度返回，可能与配置维度不一致（如配置 1536 但模型仅支持 1024），
                    // 告警提示，避免向量维度不匹配问题在入库后才暴露
                    if (dim != null && dim > 0 && !vectors.isEmpty() && !vectors.get(0).isEmpty()
                            && vectors.get(0).size() != dim) {
                        log.warn("embedding 降级后实际维度 {} 与配置维度 {} 不一致（model={}），"
                                        + "请改用支持该维度的模型或调整模型维度配置",
                                vectors.get(0).size(), dim, SdkGatewaySupport.requireModelName(target));
                    }
                    return vectors;
                } catch (Exception retryError) {
                    throw SdkGatewaySupport.translateError(provider(), retryError);
                }
            }
        } finally {
            client.close();
        }
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> dedup = dedupById(candidates);
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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", SdkGatewaySupport.requireModelName(target));
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", topN);
        body.put("return_documents", true);

        String url = httpModelFactory.resolveFullUrl(target, "rerank", DEFAULT_RERANK_PATH);
        JsonNode resp = httpClient.syncPost(url, target, body, root -> root);

        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        JsonNode results = resp.path("results");
        if (results.isArray()) {
            for (JsonNode item : results) {
                if (!item.has("index")) {
                    continue;
                }
                int idx = item.get("index").asInt();
                if (idx < 0 || idx >= docCandidates.size()) {
                    continue;
                }
                RetrievedChunk src = docCandidates.get(idx);
                Float score = item.has("relevance_score") && !item.get("relevance_score").isNull()
                        ? (float) item.get("relevance_score").asDouble() : src.getScore();
                reranked.add(RetrievedChunk.builder()
                        .id(src.getId()).text(src.getText()).score(score)
                        .contentType(src.getContentType()).metadata(src.getMetadata())
                        .kbName(src.getKbName()).docName(src.getDocName())
                        .build());
                addedIds.add(src.getId());
                if (reranked.size() >= topN) {
                    break;
                }
            }
        }
        // 补齐不足 topN 的缺口
        for (RetrievedChunk c : dedup) {
            if (addedIds.add(c.getId())) {
                reranked.add(c);
            }
            if (reranked.size() >= topN) {
                break;
            }
        }
        return reranked;
    }

    private List<RetrievedChunk> dedupById(List<RetrievedChunk> candidates) {
        List<RetrievedChunk> dedup = new ArrayList<>(candidates.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk c : candidates) {
            if (seen.add(c.getId())) {
                dedup.add(c);
            }
        }
        return dedup;
    }

    // ==================== 内部工具 ====================

    private OpenAIClient buildClient(ModelTarget target) {
        String baseUrl = SdkGatewaySupport.resolveSdkBaseUrl(target, "chat");
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://api.openai.com/v1";
        }
        return OpenAIOkHttpClient.builder()
                .apiKey(SdkGatewaySupport.resolveApiKey(target))
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private ChatCompletionCreateParams buildParams(ChatRequest request, ModelTarget target, boolean stream) {
        ChatCompletionCreateParams.Builder pb = ChatCompletionCreateParams.builder()
                .model(SdkGatewaySupport.requireModelName(target));
        if (request.getTemperature() != null) {
            pb.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            pb.topP(request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            pb.maxTokens((long) request.getMaxTokens());
        }
        for (ChatMessageParamParam message : buildMessages(request, target)) {
            pb.addMessage(message.param());
        }
        return pb.build();
    }

    private List<ChatMessageParamParam> buildMessages(ChatRequest request, ModelTarget target) {
        List<ChatMessageParamParam> messages = new ArrayList<>();
        if (request.getMessages() == null) {
            return messages;
        }
        for (ChatMessage msg : request.getMessages()) {
            ChatMessage.Role role = msg.getRole() != null ? msg.getRole() : ChatMessage.Role.USER;
            boolean hasImage = msg.getImageUrls() != null && !msg.getImageUrls().isEmpty();
            String text = msg.getContent() != null ? msg.getContent() : "";

            if (role == ChatMessage.Role.USER && hasImage) {
                List<ChatCompletionContentPart> parts = new ArrayList<>();
                if (StringUtils.hasText(text)) {
                    parts.add(ChatCompletionContentPart.ofText(
                            ChatCompletionContentPartText.builder().text(text).build()));
                }
                for (String imageUrl : msg.getImageUrls()) {
                    String url = httpModelFactory.resolveImageDataUri(imageUrl);
                    if (StringUtils.hasText(url)) {
                        parts.add(ChatCompletionContentPart.ofImageUrl(
                                ChatCompletionContentPartImage.builder()
                                        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(url).build())
                                        .build()));
                    }
                }
                messages.add(new ChatMessageParamParam(ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                                .build())));
            } else if (role == ChatMessage.Role.ASSISTANT) {
                com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.Builder mb =
                        com.openai.models.chat.completions.ChatCompletionAssistantMessageParam.builder()
                                .content(text);
                if (StringUtils.hasText(msg.getThinkingContent())) {
                    // DeepSeek 等要求历史 assistant 消息回传 reasoning_content
                    mb.putAdditionalProperty("reasoning_content", JsonValue.from(msg.getThinkingContent()));
                }
                messages.add(new ChatMessageParamParam(ChatCompletionMessageParam.ofAssistant(mb.build())));
            } else {
                messages.add(new ChatMessageParamParam(ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder().content(text).build())));
            }
        }
        return messages;
    }

    private String extractContent(ChatCompletion completion) {
        if (completion.choices() != null && !completion.choices().isEmpty()) {
            var message = completion.choices().get(0).message();
            return message.content().orElse("");
        }
        return "";
    }

    private void forwardChunk(ChatCompletionChunk chunk, StreamCallback callback) {
        if (chunk.choices() == null || chunk.choices().isEmpty()) {
            return;
        }
        var delta = chunk.choices().get(0).delta();
        // DeepSeek 等厂商在流式增量中通过 reasoning_content 下发思维链
        Object reasoning = delta._additionalProperties().get("reasoning_content");
        if (reasoning instanceof JsonValue jv) {
            String text = jv.convert(String.class);
            if (StringUtils.hasText(text)) {
                callback.onThinking(text);
            }
        }
        if (delta.content().isPresent() && StringUtils.hasText(delta.content().get())) {
            callback.onContent(delta.content().get());
        }
    }

    private List<List<Float>> extractEmbeddings(CreateEmbeddingResponse response, int expected) {
        List<List<Float>> all = new ArrayList<>();
        if (response.data() == null) {
            return all;
        }
        List<Embedding> data = response.data();
        for (int i = 0; i < expected && i < data.size(); i++) {
            Embedding emb = data.get(i);
            List<Float> vec = new ArrayList<>();
            if (emb.embedding() != null) {
                for (double d : emb.embedding()) {
                    vec.add((float) d);
                }
            }
            all.add(vec);
        }
        return all;
    }

    /** 简易包装：避免在 lambda 中暴露 SDK 类型 */
    private record ChatMessageParamParam(ChatCompletionMessageParam param) {
    }
}