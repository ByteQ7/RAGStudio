package com.byteq.ai.ragstudio.infra.sdk;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Base64ImageSource;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
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
 * Anthropic 官方 SDK 网关（anthropic-java）
 * <p>
 * 作为「Anthropic 兼容协议」的通用策略：通过自定义 {@code baseUrl} 可承载
 * Anthropic Claude / DeepSeek 的 Anthropic 兼容接口 等厂商。覆盖 chat（同步/流式）。
 * </p>
 */
@Slf4j
@Component
@Order(50)
public class AnthropicGateway implements ProviderGateway {

    private static final long STARTUP_TIMEOUT_MS = 45_000;

    private final HttpModelFactory httpModelFactory;

    public AnthropicGateway(HttpModelFactory httpModelFactory) {
        this.httpModelFactory = httpModelFactory;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public boolean supports(String providerName, String protocolName) {
        return "anthropic".equalsIgnoreCase(protocolName);
    }

    // ==================== Chat ====================

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        AnthropicClient client = buildClient(target);
        MessageCreateParams params = buildParams(request, target, false);
        return extractText(client.messages().create(params));
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        AnthropicClient client = buildStreamClient(target);
        MessageCreateParams params = buildParams(request, target, true);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean firstReceived = new AtomicBoolean(false);
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);
        AtomicReference<StreamResponse<RawMessageStreamEvent>> streamRef = new AtomicReference<>();

        try {
            StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params);
            streamRef.set(stream);
            Thread consumer = new Thread(() -> {
                try (StreamResponse<RawMessageStreamEvent> s = stream) {
                    var it = s.stream().iterator();
                    while (it.hasNext()) {
                        RawMessageStreamEvent event = it.next();
                        if (finished.get()) {
                            break;
                        }
                        if (firstReceived.compareAndSet(false, true)) {
                            firstChunk.countDown();
                        }
                        forwardEvent(event, callback);
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
            }, "anthropic-sse-consumer");
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
            throw e;
        } catch (Exception e) {
            throw SdkGatewaySupport.translateError(provider(), e);
        }
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        throw new UnsupportedOperationException("Anthropic 网关不支持文本嵌入: " + provider());
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        throw new UnsupportedOperationException("Anthropic 网关不支持重排序: " + provider());
    }

    // ==================== 内部工具 ====================

    /** 同步客户端缓存（key=apiKey@baseUrl）：每次调用新建 OkHttp 客户端会造成连接池/线程泄漏 */
    private static final java.util.concurrent.ConcurrentHashMap<String, AnthropicClient> SYNC_CLIENT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 流式客户端缓存：独立于同步客户端，使用更长的总超时 */
    private static final java.util.concurrent.ConcurrentHashMap<String, AnthropicClient> STREAM_CLIENT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 同步调用总超时（callTimeout 覆盖整个请求，含响应体读取） */
    private static final java.time.Duration SYNC_CALL_TIMEOUT = java.time.Duration.ofSeconds(120);
    /** 流式调用总超时：长回答可能远超 60s，放长到 15 分钟避免生成中途被强制截断 */
    private static final java.time.Duration STREAM_CALL_TIMEOUT = java.time.Duration.ofMinutes(15);

    private AnthropicClient buildClient(ModelTarget target) {
        final String baseUrl = resolveAnthropicBaseUrl(target);
        final String apiKey = SdkGatewaySupport.resolveApiKey(target);
        final String cacheKey = apiKey + "@" + baseUrl;
        return SYNC_CLIENT_CACHE.computeIfAbsent(cacheKey, k -> AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(SYNC_CALL_TIMEOUT)
                // SDK 自带默认 2 次重试（408/429/5xx）会叠加在自研故障转移之上放大延迟与配额压力，关闭
                .maxRetries(0)
                .build());
    }

    private AnthropicClient buildStreamClient(ModelTarget target) {
        final String baseUrl = resolveAnthropicBaseUrl(target);
        final String apiKey = SdkGatewaySupport.resolveApiKey(target);
        final String cacheKey = apiKey + "@" + baseUrl;
        return STREAM_CLIENT_CACHE.computeIfAbsent(cacheKey, k -> AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(STREAM_CALL_TIMEOUT)
                .maxRetries(0)
                .build());
    }

    private String resolveAnthropicBaseUrl(ModelTarget target) {
        String baseUrl = SdkGatewaySupport.resolveSdkBaseUrl(target, "chat");
        return StringUtils.hasText(baseUrl) ? baseUrl : "https://api.anthropic.com";
    }

    private MessageCreateParams buildParams(ChatRequest request, ModelTarget target, boolean stream) {
        MessageCreateParams.Builder pb = MessageCreateParams.builder()
                .model(SdkGatewaySupport.requireModelName(target))
                .maxTokens(request.getMaxTokens() != null && request.getMaxTokens() > 0
                        ? request.getMaxTokens() : 4096L);
        if (request.getTemperature() != null) {
            pb.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            pb.topP(request.getTopP());
        }

        List<String> systemBlocks = new ArrayList<>();
        List<MessageParam> messages = new ArrayList<>();
        String lastRole = null;
        List<com.anthropic.models.messages.ContentBlockParam> lastBlocks = null;

        if (request.getMessages() != null) {
            for (ChatMessage msg : request.getMessages()) {
                if (msg.getRole() == ChatMessage.Role.SYSTEM) {
                    if (StringUtils.hasText(msg.getContent())) {
                        systemBlocks.add(msg.getContent());
                    }
                    continue;
                }
                String role = msg.getRole() == ChatMessage.Role.ASSISTANT ? "assistant" : "user";
                List<com.anthropic.models.messages.ContentBlockParam> blocks = new ArrayList<>();
                if (StringUtils.hasText(msg.getContent())) {
                    blocks.add(com.anthropic.models.messages.ContentBlockParam.ofText(
                            TextBlockParam.builder().text(msg.getContent()).build()));
                }
                if (msg.getImageUrls() != null) {
                    for (String imageUrl : msg.getImageUrls()) {
                        ImageBlockParam img = buildImageBlock(imageUrl);
                        if (img != null) {
                            blocks.add(com.anthropic.models.messages.ContentBlockParam.ofImage(img));
                        }
                    }
                }
                if (blocks.isEmpty()) {
                    continue;
                }
                // 连续同角色合并（Anthropic API 要求 user/assistant 交替）
                if (role.equals(lastRole) && lastBlocks != null) {
                    lastBlocks.addAll(blocks);
                    continue;
                }
                lastRole = role;
                lastBlocks = blocks;
                messages.add(MessageParam.builder()
                        .role("user".equals(role) ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                        .content(MessageParam.Content.ofBlockParams(blocks))
                        .build());
            }
        }

        if (!systemBlocks.isEmpty()) {
            List<TextBlockParam> systemParam = new ArrayList<>();
            for (String text : systemBlocks) {
                systemParam.add(TextBlockParam.builder().text(text).build());
            }
            pb.system(MessageCreateParams.System.ofTextBlockParams(systemParam));
        }
        pb.messages(messages);
        return pb.build();
    }

    private ImageBlockParam buildImageBlock(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String dataUri = httpModelFactory.resolveImageDataUri(url);
        if (dataUri == null || !dataUri.startsWith("data:")) {
            return null;
        }
        int commaIdx = dataUri.indexOf(',');
        if (commaIdx < 0) {
            return null;
        }
        String meta = dataUri.substring(5, commaIdx);
        String data = dataUri.substring(commaIdx + 1);
        String mediaType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
        Base64ImageSource.MediaType type = switch (mediaType.toLowerCase()) {
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> Base64ImageSource.MediaType.IMAGE_JPEG;
        };
        return ImageBlockParam.builder()
                .source(ImageBlockParam.Source.ofBase64(
                        Base64ImageSource.builder().data(data).mediaType(type).build()))
                .build();
    }

    private String extractText(Message message) {
        if (message.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block.text().isPresent()) {
                sb.append(block.text().get());
            }
        }
        return sb.toString();
    }

    private void forwardEvent(RawMessageStreamEvent event, StreamCallback callback) {
        if (!event.isContentBlockDelta()) {
            return;
        }
        var delta = event.asContentBlockDelta().delta();
        if (delta.isText()) {
            String text = delta.asText().text();
            if (StringUtils.hasText(text)) {
                callback.onContent(text);
            }
        } else if (delta.isThinking()) {
            String thinking = delta.asThinking().thinking();
            if (StringUtils.hasText(thinking)) {
                callback.onThinking(thinking);
            }
        }
    }
}