package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volcengine.ark.runtime.interceptor.AuthenticationInterceptor;
import com.volcengine.ark.runtime.interceptor.RequestIdInterceptor;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChoice;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.model.embeddings.EmbeddingRequest;
import com.volcengine.ark.runtime.model.embeddings.EmbeddingResult;
import com.volcengine.ark.runtime.service.ArkApi;
import com.volcengine.ark.runtime.service.ArkService;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import retrofit2.Retrofit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 火山方舟（VolcEngine Ark）官方 SDK 网关（volcengine-java-sdk-ark-runtime）
 * <p>
 * 封装 ark-runtime（OpenAI 兼容接口），覆盖 chat（同步/流式）与 embedding。
 * 通过自定义 baseUrl（默认 https://ark.cn-beijing.volces.com/api/v3）构建 Retrofit。
 * </p>
 */
@Slf4j
@Component
@Order(30)
public class VolcEngineGateway implements ProviderGateway {

    private static final long STARTUP_TIMEOUT_MS = 45_000;
    private static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    private final HttpModelFactory httpModelFactory;

    public VolcEngineGateway(HttpModelFactory httpModelFactory) {
        this.httpModelFactory = httpModelFactory;
    }

    @Override
    public String provider() {
        return "volcengine";
    }

    @Override
    public boolean supports(String providerName, String protocolName) {
        return SdkGatewaySupport.matchesAlias(providerName, "volcengine", "volcano", "火山引擎", "doubao", "豆包")
                && "openai".equalsIgnoreCase(protocolName);
    }

    // ==================== Chat ====================

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        ArkService service = buildService(target);
        try {
            ChatCompletionRequest req = buildRequest(request, target, false);
            ChatCompletionResult result = service.createChatCompletion(req);
            return extractText(result);
        } finally {
        }
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        ArkService service = buildService(target);
        ChatCompletionRequest req = buildRequest(request, target, true);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean firstReceived = new AtomicBoolean(false);
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);

        try {
            Flowable<com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk> flow =
                    service.streamChatCompletion(req);
            Disposable[] holder = new Disposable[1];
            holder[0] = flow.subscribe(
                    chunk -> {
                        if (firstReceived.compareAndSet(false, true)) {
                            firstChunk.countDown();
                        }
                        forwardChunk(chunk, callback);
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
            throw e;
        } catch (Exception e) {
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    // ==================== Embedding ====================

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        ArkService service = buildService(target);
        try {
            EmbeddingRequest req = new EmbeddingRequest();
            req.setModel(SdkGatewaySupport.requireModelName(target));
            req.setInput(texts);
            EmbeddingResult result = service.createEmbeddings(req);
            return extractEmbeddings(result, texts.size());
        } finally {
        }
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        throw new UnsupportedOperationException("volcengine 网关不支持重排序: " + provider());
    }

    // ==================== 内部工具 ====================

    private ArkService buildService(ModelTarget target) {
        String apiKey = SdkGatewaySupport.resolveApiKey(target);
        String baseUrl = SdkGatewaySupport.resolveSdkBaseUrl(target, "chat");
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = DEFAULT_BASE_URL;
        }
        baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

        ObjectMapper mapper = ArkService.defaultObjectMapper();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AuthenticationInterceptor(apiKey))
                .addInterceptor(new RequestIdInterceptor())
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .build();
        Retrofit retrofit = ArkService.defaultRetrofit(client, mapper, baseUrl, null);
        ArkApi api = retrofit.create(ArkApi.class);
        return new ArkService(api);
    }

    private ChatCompletionRequest buildRequest(ChatRequest request, ModelTarget target, boolean stream) {
        ChatCompletionRequest req = new ChatCompletionRequest();
        req.setModel(SdkGatewaySupport.requireModelName(target));
        req.setMessages(convertMessages(request));
        req.setStream(stream);
        if (request.getTemperature() != null) {
            req.setTemperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            req.setTopP(request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            req.setMaxTokens(request.getMaxTokens());
        }
        return req;
    }

    private List<ChatMessage> convertMessages(ChatRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        if (request.getMessages() == null) {
            return messages;
        }
        for (com.byteq.ai.ragstudio.framework.convention.ChatMessage msg : request.getMessages()) {
            ChatMessage m = new ChatMessage();
            if (msg.getRole() == null || msg.getRole() == com.byteq.ai.ragstudio.framework.convention.ChatMessage.Role.USER) {
                m.setRole(ChatMessageRole.USER);
            } else if (msg.getRole() == com.byteq.ai.ragstudio.framework.convention.ChatMessage.Role.ASSISTANT) {
                m.setRole(ChatMessageRole.ASSISTANT);
            } else {
                m.setRole(ChatMessageRole.SYSTEM);
            }
            m.setContent(msg.getContent() != null ? msg.getContent() : "");
            if (msg.getRole() == com.byteq.ai.ragstudio.framework.convention.ChatMessage.Role.ASSISTANT
                    && StringUtils.hasText(msg.getThinkingContent())) {
                m.setReasoningContent(msg.getThinkingContent());
            }
            messages.add(m);
        }
        return messages;
    }

    private String extractText(ChatCompletionResult result) {
        if (result == null || result.getChoices() == null || result.getChoices().isEmpty()) {
            return "";
        }
        ChatCompletionChoice choice = result.getChoices().get(0);
        if (choice != null && choice.getMessage() != null) {
            return choice.getMessage().stringContent();
        }
        return "";
    }

    private void forwardChunk(com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk chunk,
                              StreamCallback callback) {
        if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            return;
        }
        ChatCompletionChoice choice = chunk.getChoices().get(0);
        if (choice.getMessage() == null) {
            return;
        }
        ChatMessage message = choice.getMessage();
        if (StringUtils.hasText(message.getReasoningContent())) {
            callback.onThinking(message.getReasoningContent());
        }
        String content = message.stringContent();
        if (StringUtils.hasText(content)) {
            callback.onContent(content);
        }
    }

    private List<List<Float>> extractEmbeddings(EmbeddingResult result, int expected) {
        List<List<Float>> all = new ArrayList<>();
        if (result == null || result.getData() == null) {
            return all;
        }
        for (int i = 0; i < expected && i < result.getData().size(); i++) {
            com.volcengine.ark.runtime.model.embeddings.Embedding emb = result.getData().get(i);
            List<Float> vec = new ArrayList<>();
            if (emb.getEmbedding() != null) {
                for (Number n : emb.getEmbedding()) {
                    vec.add(n.floatValue());
                }
            }
            all.add(vec);
        }
        return all;
    }
}