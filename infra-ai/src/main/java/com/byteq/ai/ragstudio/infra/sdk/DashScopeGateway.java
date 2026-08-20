package com.byteq.ai.ragstudio.infra.sdk;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MessageContentImageURL;
import com.alibaba.dashscope.common.MessageContentText;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.common.ImageURL;
import com.alibaba.dashscope.embeddings.MultiModalEmbedding;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemBase;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemImage;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingItemText;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingParam;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResult;
import com.alibaba.dashscope.embeddings.MultiModalEmbeddingResultItem;
import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolFunction;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.reasoning.ReasoningRouter;
import com.byteq.ai.ragstudio.infra.rerank.DashScopeMultimodalRerankHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 阿里百炼（DashScope）官方 SDK 网关
 * <p>
 * 全能力走官方 SDK（dashscope-sdk-java）：
 * <ul>
 *   <li>chat / streamChat → {@link Generation}（qwen 系列原生接口，含 thinking / tools）</li>
 *   <li>embedBatch → {@link TextEmbedding}（text-embedding-v3/v4）</li>
 *   <li>embedImages → {@link MultiModalEmbedding}（qwen3-vl-embedding，多模态图文混合）</li>
 *   <li>rerank → {@link TextReRank}（文本）{@link DashScopeMultimodalRerankHelper}（多模态降级）</li>
 * </ul>
 * </p>
 * <p>
 * 协议判定：仅在 provider 命中百炼别名且协议为 dashscope（原生接口）时接管；
 * 配置为 openai（兼容模式）的百炼模型由 {@link OpenAiGateway} 处理。
 * </p>
 */
@Slf4j
@Component
@Order(10)
public class DashScopeGateway implements ProviderGateway {

    private static final String DASHSCOPE_PROTOCOL = "dashscope";
    private static final long STARTUP_TIMEOUT_MS = 45_000;

    private static final Gson GSON = new Gson();

    private final HttpModelFactory httpModelFactory;
    private final ReasoningRouter reasoningRouter;
    private final DashScopeMultimodalRerankHelper multimodalRerankHelper;

    public DashScopeGateway(HttpModelFactory httpModelFactory,
                            ReasoningRouter reasoningRouter,
                            DashScopeMultimodalRerankHelper multimodalRerankHelper) {
        this.httpModelFactory = httpModelFactory;
        this.reasoningRouter = reasoningRouter;
        this.multimodalRerankHelper = multimodalRerankHelper;
    }

    @Override
    public String provider() {
        return "bailian";
    }

    @Override
    public boolean supports(String providerName, String protocolName) {
        return SdkGatewaySupport.matchesAlias(providerName, "bailian", "百炼", "阿里云", "alibaba", "dashscope")
                && DASHSCOPE_PROTOCOL.equalsIgnoreCase(protocolName);
    }

    // ==================== Chat ====================

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        GenerationParam param = buildGenerationParam(request, target, false);
        try {
            GenerationResult result = new Generation(
                    Protocol.HTTP.getValue(),
                    SdkGatewaySupport.normalizeDashScopeBaseUrl(SdkGatewaySupport.resolveBaseUrl(target)))
                    .call(param);
            return extractText(result);
        } catch (Exception e) {
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        GenerationParam param = buildGenerationParam(request, target, true);
        Generation generation = new Generation(
                Protocol.HTTP.getValue(),
                SdkGatewaySupport.normalizeDashScopeBaseUrl(SdkGatewaySupport.resolveBaseUrl(target)));

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean firstReceived = new AtomicBoolean(false);
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);

        try {
            Flowable<GenerationResult> flow = generation.streamCall(param);
            Disposable[] holder = new Disposable[1];
            holder[0] = flow.subscribe(
                    result -> {
                        if (firstReceived.compareAndSet(false, true)) {
                            firstChunk.countDown();
                        }
                        forwardResult(result, callback);
                    },
                    error -> {
                        if (firstReceived.compareAndSet(false, true)) {
                            startupError.set(error);
                            firstChunk.countDown();
                        } else if (finished.compareAndSet(false, true)) {
                            callback.onError(error);
                        }
                    },
                    () -> {
                        if (firstReceived.compareAndSet(false, true)) {
                            firstChunk.countDown();
                        }
                        if (finished.compareAndSet(false, true)) {
                            callback.onComplete();
                        }
                    });

            // 同步等待首包：启动失败（网络/鉴权/超时）在此同步抛出，路由层才能切换 fallback 模型
            try {
                if (!firstChunk.await(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    holder[0].dispose();
                    throw new ModelClientException("流式请求启动超时",
                            ModelClientErrorType.NETWORK_ERROR, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                holder[0].dispose();
                throw new ModelClientException("流式请求启动被中断",
                        ModelClientErrorType.NETWORK_ERROR, null, e);
            }
            Throwable startupFailure = startupError.get();
            if (startupFailure != null) {
                holder[0].dispose();
                throw SdkGatewaySupport.translateError(provider(), startupFailure);
            }

            return () -> {
                if (finished.compareAndSet(false, true)) {
                    holder[0].dispose();
                }
            };
        } catch (ModelClientException e) {
            throw e;
        } catch (Exception e) {
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    private void forwardResult(GenerationResult result, StreamCallback callback) {
        if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            return;
        }
        GenerationOutput.Choice choice = result.getOutput().getChoices().get(0);
        if (choice.getMessage() == null) {
            return;
        }
        Message message = choice.getMessage();
        if (StringUtils.hasText(message.getReasoningContent())) {
            callback.onThinking(message.getReasoningContent());
        }
        if (StringUtils.hasText(message.getContent())) {
            callback.onContent(message.getContent());
        }
    }

    private String extractText(GenerationResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            return "";
        }
        Message message = result.getOutput().getChoices().get(0).getMessage();
        if (message != null && StringUtils.hasText(message.getContent())) {
            return message.getContent();
        }
        return "";
    }

    // ==================== Embedding ====================

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .model(SdkGatewaySupport.requireModelName(target))
                .texts(texts)
                .textType(TextEmbeddingParam.TextType.DOCUMENT)
                .dimension(resolveDimension(target))
                .apiKey(SdkGatewaySupport.resolveApiKey(target))
                .build();
        try {
            TextEmbeddingResult result = new TextEmbedding(
                    SdkGatewaySupport.normalizeDashScopeBaseUrl(SdkGatewaySupport.resolveBaseUrl(target)))
                    .call(param);
            return extractTextEmbeddings(result, texts.size());
        } catch (Exception e) {
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    private List<List<Float>> extractTextEmbeddings(TextEmbeddingResult result, int expected) {
        List<List<Float>> all = new ArrayList<>();
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            return all;
        }
        List<TextEmbeddingResultItem> items = result.getOutput().getEmbeddings();
        // 响应顺序与请求顺序一致，按 textIndex 补位保持输出对齐
        for (int i = 0; i < expected; i++) {
            List<Float> vec = null;
            for (TextEmbeddingResultItem item : items) {
                Integer idx = item.getTextIndex();
                if (idx != null && idx == i && item.getEmbedding() != null) {
                    vec = toFloats(item.getEmbedding());
                    break;
                }
            }
            all.add(vec != null ? vec : List.of());
        }
        return all;
    }

    @Override
    public List<List<Float>> embedImages(List<String> imageBase64List, ModelTarget target) {
        if (imageBase64List == null || imageBase64List.isEmpty()) {
            return List.of();
        }
        List<MultiModalEmbeddingItemBase> contents = new ArrayList<>(imageBase64List.size());
        for (String imageBase64 : imageBase64List) {
            contents.add(new MultiModalEmbeddingItemImage(imageBase64));
        }
        MultiModalEmbeddingParam param = MultiModalEmbeddingParam.builder()
                .model(SdkGatewaySupport.requireModelName(target))
                .contents(contents)
                .apiKey(SdkGatewaySupport.resolveApiKey(target))
                .build();
        try {
            MultiModalEmbeddingResult result = new MultiModalEmbedding(
                    SdkGatewaySupport.normalizeDashScopeBaseUrl(SdkGatewaySupport.resolveBaseUrl(target)))
                    .call(param);
            return extractMultimodalEmbeddings(result, imageBase64List.size());
        } catch (Exception e) {
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    private List<List<Float>> extractMultimodalEmbeddings(MultiModalEmbeddingResult result, int expected) {
        List<List<Float>> all = new ArrayList<>();
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            return all;
        }
        List<MultiModalEmbeddingResultItem> items = result.getOutput().getEmbeddings();
        for (int i = 0; i < expected; i++) {
            List<Float> vec = null;
            for (MultiModalEmbeddingResultItem item : items) {
                Integer idx = item.getIndex();
                if (idx != null && idx == i && item.getEmbedding() != null) {
                    vec = toFloats(item.getEmbedding());
                    break;
                }
            }
            all.add(vec != null ? vec : List.of());
        }
        return all;
    }

    // ==================== Rerank ====================

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        boolean multimodal = target.candidate() != null
                && Boolean.TRUE.equals(target.candidate().getSupportsMultimodal());
        if (multimodal) {
            // 多模态 rerank（qwen3-vl-rerank，图文混合）SDK 暂不支持，走既有 HTTP 路径
            return multimodalRerankHelper.rerank(query, candidates, topN, target);
        }
        List<String> documents = new ArrayList<>();
        List<RetrievedChunk> docCandidates = new ArrayList<>();
        for (RetrievedChunk chunk : candidates) {
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

        TextReRankParam param = TextReRankParam.builder()
                .model(SdkGatewaySupport.requireModelName(target))
                .query(query)
                .documents(documents)
                .topN(topN)
                .returnDocuments(true)
                .apiKey(SdkGatewaySupport.resolveApiKey(target))
                .build();
        try {
            TextReRankResult result = new TextReRank(
                    Protocol.HTTP.getValue(),
                    SdkGatewaySupport.normalizeDashScopeBaseUrl(SdkGatewaySupport.resolveBaseUrl(target)))
                    .call(param);
            return parseRerankResult(result, docCandidates, candidates, topN);
        } catch (Exception e) {
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    private List<RetrievedChunk> parseRerankResult(TextReRankResult result,
                                                   List<RetrievedChunk> docCandidates,
                                                   List<RetrievedChunk> candidates,
                                                   int topN) {
        List<RetrievedChunk> reranked = new ArrayList<>();
        if (result == null || result.getOutput() == null || result.getOutput().getResults() == null) {
            return fillShortfall(reranked, candidates, topN);
        }
        List<TextReRankOutput.Result> results = result.getOutput().getResults();
        java.util.Set<String> addedIds = new java.util.HashSet<>();
        for (TextReRankOutput.Result item : results) {
            Integer idx = item.getIndex();
            if (idx == null || idx < 0 || idx >= docCandidates.size()) {
                continue;
            }
            RetrievedChunk src = docCandidates.get(idx);
            Float score = item.getRelevanceScore() != null ? item.getRelevanceScore().floatValue() : src.getScore();
            RetrievedChunk hit = RetrievedChunk.builder()
                    .id(src.getId()).text(src.getText()).score(score)
                    .contentType(src.getContentType()).metadata(src.getMetadata())
                    .kbName(src.getKbName()).docName(src.getDocName())
                    .build();
            reranked.add(hit);
            addedIds.add(src.getId());
            if (reranked.size() >= topN) {
                break;
            }
        }
        return fillShortfall(reranked, candidates, topN);
    }

    private List<RetrievedChunk> fillShortfall(List<RetrievedChunk> reranked, List<RetrievedChunk> candidates, int topN) {
        java.util.Set<String> addedIds = new java.util.HashSet<>();
        for (RetrievedChunk c : reranked) {
            addedIds.add(c.getId());
        }
        for (RetrievedChunk c : candidates) {
            if (addedIds.add(c.getId())) {
                reranked.add(c);
            }
            if (reranked.size() >= topN) {
                break;
            }
        }
        return reranked;
    }

    // ==================== 内部工具 ====================

    private GenerationParam buildGenerationParam(ChatRequest request, ModelTarget target, boolean stream) {
        GenerationParam.GenerationParamBuilder builder = GenerationParam.builder()
                .model(SdkGatewaySupport.requireModelName(target))
                .messages(convertMessages(request))
                .apiKey(SdkGatewaySupport.resolveApiKey(target))
                .incrementalOutput(stream);

        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature().floatValue());
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getTopK() != null) {
            builder.topK(request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            builder.maxTokens(request.getMaxTokens());
        }

        // 响应格式约束
        if (StringUtils.hasText(request.getResponseFormat())) {
            if ("json_object".equals(request.getResponseFormat())) {
                builder.resultFormat("json_object");
            }
        }

        // 推理深度：ReasoningRouter 产物映射到 DashScope 原生 thinking 参数
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        if (thinkingLevel > 0 && reasoningRouter != null) {
            Map<String, Object> reasoningParams =
                    reasoningRouter.route(target.candidate().getModel(), thinkingLevel);
            applyReasoningParams(builder, reasoningParams);
        }

        // 工具定义（OpenAI 格式 → DashScope ToolFunction）
        List<ToolFunction> tools = convertTools(request);
        if (tools != null && !tools.isEmpty()) {
            builder.tools(new ArrayList<>(tools));
        }

        return builder.build();
    }

    private void applyReasoningParams(GenerationParam.GenerationParamBuilder builder, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "enable_thinking" -> {
                    if (value instanceof Boolean b) {
                        builder.enableThinking(b);
                    }
                }
                case "budget_tokens" -> {
                    if (value instanceof Number n) {
                        builder.thinkingBudget(n.intValue());
                    }
                }
                default -> builder.parameter(key, value);
            }
        }
    }

    private List<Message> convertMessages(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.getMessages() == null) {
            return messages;
        }
        for (ChatMessage msg : request.getMessages()) {
            Role role = mapRole(msg);
            Message.MessageBuilder<?, ?> builder = Message.builder().role(role.getValue());
            boolean hasImage = msg.getImageUrls() != null && !msg.getImageUrls().isEmpty();

            if (role == Role.USER && hasImage) {
                List<com.alibaba.dashscope.common.MessageContentBase> contents = new ArrayList<>();
                if (StringUtils.hasText(msg.getContent())) {
                    contents.add(MessageContentText.builder().text(msg.getContent()).build());
                }
                for (String imageUrl : msg.getImageUrls()) {
                    String url = httpModelFactory.resolveImageDataUri(imageUrl);
                    if (StringUtils.hasText(url)) {
                        contents.add(MessageContentImageURL.builder()
                                .imageURL(ImageURL.builder().url(url).build())
                                .build());
                    }
                }
                builder.contents(contents);
            } else {
                builder.content(msg.getContent() != null ? msg.getContent() : "");
            }

            if (msg.getRole() == ChatMessage.Role.ASSISTANT && StringUtils.hasText(msg.getThinkingContent())) {
                builder.reasoningContent(msg.getThinkingContent());
            }
            messages.add(builder.build());
        }
        return messages;
    }

    private Role mapRole(ChatMessage msg) {
        if (msg.getRole() == null || msg.getRole() == ChatMessage.Role.USER) {
            return Role.USER;
        }
        if (msg.getRole() == ChatMessage.Role.ASSISTANT) {
            return Role.ASSISTANT;
        }
        // SYSTEM / OBSERVATION → system
        return Role.SYSTEM;
    }

    private List<ToolFunction> convertTools(ChatRequest request) {
        if (request.getTools() == null || request.getTools().isEmpty()) {
            return null;
        }
        List<ToolFunction> tools = new ArrayList<>();
        for (Map<String, Object> tool : request.getTools()) {
            Object fn = tool.get("function");
            if (!(fn instanceof Map<?, ?> functionMap)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) functionMap;
            String name = String.valueOf(function.getOrDefault("name", ""));
            if (name.isBlank()) {
                continue;
            }
            FunctionDefinition.FunctionDefinitionBuilder fb = FunctionDefinition.builder().name(name);
            if (function.get("description") != null) {
                fb.description(String.valueOf(function.get("description")));
            }
            if (function.get("parameters") instanceof Map<?, ?> params) {
                fb.parameters(GSON.toJsonTree(params).getAsJsonObject());
            }
            tools.add(ToolFunction.builder().function(fb.build()).build());
        }
        return tools.isEmpty() ? null : tools;
    }

    private Integer resolveDimension(ModelTarget target) {
        if (target.candidate() == null) {
            return null;
        }
        Integer dimension = target.candidate().getDimension();
        if (dimension != null && dimension > 0) {
            return dimension;
        }
        return null;
    }

    private List<Float> toFloats(List<Double> doubles) {
        List<Float> floats = new ArrayList<>(doubles.size());
        for (Double d : doubles) {
            floats.add(d.floatValue());
        }
        return floats;
    }
}