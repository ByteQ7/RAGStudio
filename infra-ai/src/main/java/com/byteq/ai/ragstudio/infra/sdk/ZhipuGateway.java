package com.byteq.ai.ragstudio.infra.sdk;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.embedding.EmbeddingCreateParams;
import ai.z.openapi.service.embedding.EmbeddingResponse;
import ai.z.openapi.service.embedding.EmbeddingResult;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import ai.z.openapi.service.model.ChatThinking;
import ai.z.openapi.service.model.Choice;
import ai.z.openapi.service.model.Delta;
import ai.z.openapi.service.model.ModelData;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智谱 Z.AI 官方 SDK 网关（zai-sdk）
 * <p>
 * 封装 {@code ai.z.openapi} zai-sdk，覆盖 chat（同步/流式，含 thinking）与 embedding。
 * </p>
 */
@Slf4j
@Component
@Order(20)
public class ZhipuGateway implements ProviderGateway {

    private static final long STARTUP_TIMEOUT_MS = 45_000;

    private final HttpModelFactory httpModelFactory;

    public ZhipuGateway(HttpModelFactory httpModelFactory) {
        this.httpModelFactory = httpModelFactory;
    }

    @Override
    public String provider() {
        return "zhipu";
    }

    @Override
    public boolean supports(String providerName, String protocolName) {
        return SdkGatewaySupport.matchesAlias(providerName, "zhipu", "zhipuai", "智谱", "智谱AI", "bigmodel", "zai")
                && "openai".equalsIgnoreCase(protocolName);
    }

    // ==================== Chat ====================

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        ZhipuAiClient client = buildClient(target);
        try {
            ChatCompletionCreateParams params = buildParams(request, target, false);
            ChatCompletionResponse response = client.chat().createChatCompletion(params);
            if (!response.isSuccess()) {
                throw new ModelClientException("zhipu chat 失败: " + response.getMsg(),
                        ModelClientErrorType.PROVIDER_ERROR, null);
            }
            return extractText(response.getData());
        } finally {
            client.close();
        }
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        ZhipuAiClient client = buildClient(target);
        ChatCompletionCreateParams params = buildParams(request, target, true);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean firstReceived = new AtomicBoolean(false);
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);

        try {
            ChatCompletionResponse response = client.chat().createChatCompletion(params);
            if (!response.isSuccess()) {
                throw new ModelClientException("zhipu 流式启动失败: " + response.getMsg(),
                        ModelClientErrorType.PROVIDER_ERROR, null);
            }
            Flowable<ModelData> flowable = response.getFlowable();
            Disposable[] holder = new Disposable[1];
            holder[0] = flowable.subscribe(
                    data -> {
                        if (firstReceived.compareAndSet(false, true)) {
                            firstChunk.countDown();
                        }
                        forwardData(data, callback);
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

            try {
                if (!firstChunk.await(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    holder[0].dispose();
                    throw new ModelClientException("流式请求启动超时", ModelClientErrorType.NETWORK_ERROR, null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                holder[0].dispose();
                throw new ModelClientException("流式请求启动被中断", ModelClientErrorType.NETWORK_ERROR, null, e);
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
        ZhipuAiClient client = buildClient(target);
        try {
            EmbeddingCreateParams.EmbeddingCreateParamsBuilder pb = EmbeddingCreateParams.builder()
                    .model(SdkGatewaySupport.requireModelName(target))
                    .input(texts);
            Integer dim = target.candidate() != null ? target.candidate().getDimension() : null;
            if (dim != null && dim > 0) {
                pb.dimensions(dim);
            }
            EmbeddingResponse response = client.embeddings().createEmbeddings(pb.build());
            if (!response.isSuccess()) {
                throw new ModelClientException("zhipu embedding 失败: " + response.getMsg(),
                        ModelClientErrorType.PROVIDER_ERROR, null);
            }
            return extractEmbeddings(response.getData(), texts.size());
        } finally {
            client.close();
        }
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        throw new UnsupportedOperationException("zhipu 网关不支持重排序: " + provider());
    }

    // ==================== 内部工具 ====================

    private ZhipuAiClient buildClient(ModelTarget target) {
        ZhipuAiClient.Builder builder = ZhipuAiClient.builder().ofZHIPU();
        String baseUrl = SdkGatewaySupport.resolveSdkBaseUrl(target, "chat");
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        String apiKey = SdkGatewaySupport.resolveApiKey(target);
        if (StringUtils.hasText(apiKey)) {
            builder.apiKey(apiKey);
        }
        return builder.build();
    }

    private ChatCompletionCreateParams buildParams(ChatRequest request, ModelTarget target, boolean stream) {
        ChatCompletionCreateParams.ChatCompletionCreateParamsBuilder pb = ChatCompletionCreateParams.builder()
                .model(SdkGatewaySupport.requireModelName(target))
                .messages(convertMessages(request))
                .stream(stream);
        if (request.getTemperature() != null) {
            pb.temperature(request.getTemperature().floatValue());
        }
        if (request.getTopP() != null) {
            pb.topP(request.getTopP().floatValue());
        }

        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        pb.thinking(ChatThinking.builder().type(thinkingLevel > 0 ? "enabled" : "disabled").build());
        return pb.build();
    }

    private List<ChatMessage> convertMessages(ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (request.getMessages() == null) {
            return messages;
        }
        for (com.byteq.ai.ragstudio.framework.convention.ChatMessage msg : request.getMessages()) {
            ChatMessage.ChatMessageBuilder mb = ChatMessage.builder()
                    .role(wireRole(msg))
                    .content(msg.getContent() != null ? msg.getContent() : "");
            if (msg.getRole() == com.byteq.ai.ragstudio.framework.convention.ChatMessage.Role.ASSISTANT
                    && StringUtils.hasText(msg.getThinkingContent())) {
                mb.reasoningContent(msg.getThinkingContent());
            }
            messages.add(mb.build());
        }
        return messages;
    }

    private String wireRole(com.byteq.ai.ragstudio.framework.convention.ChatMessage msg) {
        if (msg.getRole() == null || msg.getRole() == com.byteq.ai.ragstudio.framework.convention.ChatMessage.Role.USER) {
            return ChatMessageRole.USER.value();
        }
        if (msg.getRole() == com.byteq.ai.ragstudio.framework.convention.ChatMessage.Role.ASSISTANT) {
            return ChatMessageRole.ASSISTANT.value();
        }
        return ChatMessageRole.SYSTEM.value();
    }

    private String extractText(ModelData data) {
        if (data == null || data.getChoices() == null || data.getChoices().isEmpty()) {
            return "";
        }
        Choice choice = data.getChoices().get(0);
        if (choice != null && choice.getMessage() != null && choice.getMessage().getContent() != null) {
            Object content = choice.getMessage().getContent();
            if (content instanceof String s) {
                return s;
            }
        }
        return "";
    }

    private void forwardData(ModelData data, StreamCallback callback) {
        if (data == null || data.getChoices() == null || data.getChoices().isEmpty()) {
            return;
        }
        Choice choice = data.getChoices().get(0);
        Delta delta = choice.getDelta();
        if (delta == null) {
            return;
        }
        if (StringUtils.hasText(delta.getReasoningContent())) {
            callback.onThinking(delta.getReasoningContent());
        }
        if (StringUtils.hasText(delta.getContent())) {
            callback.onContent(delta.getContent());
        }
    }

    private List<List<Float>> extractEmbeddings(EmbeddingResult result, int expected) {
        List<List<Float>> all = new ArrayList<>();
        if (result == null || result.getData() == null) {
            return all;
        }
        for (int i = 0; i < expected && i < result.getData().size(); i++) {
            ai.z.openapi.service.embedding.Embedding emb = result.getData().get(i);
            List<Float> vec = new ArrayList<>();
            if (emb.getEmbedding() != null) {
                for (Double d : emb.getEmbedding()) {
                    vec.add(d.floatValue());
                }
            }
            all.add(vec);
        }
        return all;
    }
}