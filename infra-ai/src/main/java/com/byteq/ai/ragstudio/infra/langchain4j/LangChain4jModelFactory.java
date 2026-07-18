package com.byteq.ai.ragstudio.infra.langchain4j;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.reasoning.ReasoningRouter;
import com.byteq.ai.ragstudio.infra.springai.AiLogHolder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j 模型工厂
 * <p>
 * 根据数据库配置动态创建 LangChain4j 模型实例。
 * 管理三组独立缓存：
 * <ul>
 *   <li>{@link ChatModel} —— 同步对话</li>
 *   <li>{@link StreamingChatModel} —— 流式对话</li>
 *   <li>{@link EmbeddingModel} —— 文本嵌入</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class LangChain4jModelFactory {

    private static final int MAX_CACHE_SIZE = 64;
    private static final int READ_TIMEOUT_SECONDS = 120;

    private final S3Client s3Client;
    private final ReasoningRouter reasoningRouter;

    private final Map<String, ChatModel> syncChatCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamChatCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingCache = new ConcurrentHashMap<>();

    public LangChain4jModelFactory(S3Client s3Client, ReasoningRouter reasoningRouter) {
        this.s3Client = s3Client;
        this.reasoningRouter = reasoningRouter;
    }

    public ReasoningRouter getReasoningRouter() {
        return reasoningRouter;
    }

    // ==================== 缓存获取 ====================

    public ChatModel getOrCreateChatModel(ModelTarget target) {
        evictIfNecessary(syncChatCache);
        return syncChatCache.computeIfAbsent(target.id(), id -> createChatModel(target));
    }

    public StreamingChatModel getOrCreateStreamingChatModel(ModelTarget target) {
        evictIfNecessary(streamChatCache);
        return streamChatCache.computeIfAbsent(target.id(), id -> createStreamingChatModel(target));
    }

    public EmbeddingModel getOrCreateEmbeddingModel(ModelTarget target) {
        evictIfNecessary(embeddingCache);
        return embeddingCache.computeIfAbsent(target.id(), id -> createEmbeddingModel(target));
    }

    // ==================== 模型创建 ====================

    private ChatModel createChatModel(ModelTarget target) {
        String baseUrl = resolveLangChainBaseUrl(target, "chat");
        String apiKey = resolveApiKey(target);
        String modelName = target.candidate().getModel();

        log.info("创建 OpenAI-compatible ChatModel: provider={}, model={}, baseUrl={}",
                target.candidate().getProvider(), modelName, baseUrl);

        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "no-key")
                .modelName(modelName)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .maxRetries(0)
                .build();
    }

    private StreamingChatModel createStreamingChatModel(ModelTarget target) {
        String baseUrl = resolveLangChainBaseUrl(target, "chat");
        String apiKey = resolveApiKey(target);
        String modelName = target.candidate().getModel();

        log.info("创建 OpenAI-compatible StreamingChatModel: provider={}, model={}, baseUrl={}",
                target.candidate().getProvider(), modelName, baseUrl);

        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "no-key")
                .modelName(modelName)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .build();
    }

    private EmbeddingModel createEmbeddingModel(ModelTarget target) {
        String baseUrl = resolveLangChainBaseUrl(target, "embedding");
        String apiKey = resolveApiKey(target);
        String modelName = target.candidate().getModel();
        Integer dimension = target.candidate().getDimension();

        log.info("创建 OpenAI-compatible EmbeddingModel: provider={}, model={}, dimension={}, baseUrl={}",
                target.candidate().getProvider(), modelName, dimension, baseUrl);

        // OpenAiEmbeddingModel 使用静态 builder() 方法，直接链式调用
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "no-key")
                .modelName(modelName)
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .maxRetries(0);

        if (dimension != null && dimension > 0) {
            builder.dimensions(dimension);
        }

        return builder.build();
    }

    // ==================== URL / Key 解析 ====================

    public String resolveBaseUrl(ModelTarget target) {
        DynamicModelConfig.ModelEntry candidate = target.candidate();
        DynamicModelConfig.ProviderEntry provider = target.provider();

        if (candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl().replaceAll("/+$", "");
        }
        return provider.getUrl().replaceAll("/+$", "");
    }

    public String resolveLangChainBaseUrl(ModelTarget target, String endpointType) {
        String baseUrl = resolveBaseUrl(target);
        String endpointPath = resolveEndpointPath(target, endpointType);
        if (endpointPath != null && !endpointPath.isBlank()) {
            String path = endpointPath.trim();
            // LangChain4j 会自动追加 /chat/completions 或 /embeddings
            // 所以需要从自定义端点路径中去除这些后缀
            if (path.endsWith("/chat/completions")) {
                String prefix = path.substring(0, path.length() - "/chat/completions".length());
                if (!prefix.isEmpty()) return baseUrl + prefix;
            } else if (path.endsWith("/embeddings")) {
                String prefix = path.substring(0, path.length() - "/embeddings".length());
                if (!prefix.isEmpty()) return baseUrl + prefix;
            } else if (path.startsWith("/")) {
                // fallback: 只去掉最后一段
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash > 0) {
                    return baseUrl + path.substring(0, lastSlash);
                }
            }
        }
        return baseUrl;
    }

    public String resolveApiKey(ModelTarget target) {
        return target.provider().getApiKey();
    }

    private String resolveEndpointPath(ModelTarget target, String endpointKey) {
        Map<String, String> endpoints = target.provider().getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) return null;
        String path = endpoints.get(endpointKey);
        return (path != null && !path.isBlank()) ? path : null;
    }

    // ==================== 消息转换 ====================

    /**
     * 将项目的 ChatRequest 转换为 LangChain4j 的消息列表
     */
    public List<dev.langchain4j.data.message.ChatMessage> toMessages(ChatRequest request) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
        if (request.getMessages() == null) return messages;

        for (ChatMessage msg : request.getMessages()) {
            switch (msg.getRole()) {
                case SYSTEM -> messages.add(new SystemMessage(
                        msg.getContent() != null ? msg.getContent() : ""));
                case USER -> messages.add(buildUserMessage(msg));
                case ASSISTANT -> messages.add(new AiMessage(
                        msg.getContent() != null ? msg.getContent() : ""));
            }
        }
        return messages;
    }

    private UserMessage buildUserMessage(ChatMessage msg) {
        String text = msg.getContent() != null ? msg.getContent() : "";
        List<String> imageUrls = msg.getImageUrls();

        if (imageUrls != null && !imageUrls.isEmpty()) {
            List<dev.langchain4j.data.message.Content> contents = new ArrayList<>();
            contents.add(new TextContent(text));

            for (String url : imageUrls) {
                String dataUri = resolveImageDataUri(url);
                if (dataUri != null && !dataUri.isEmpty() && !dataUri.equals(url)) {
                    contents.add(new ImageContent(dataUri));
                }
            }
            return new UserMessage(contents);
        }

        return new UserMessage(text);
    }

    // ==================== 请求参数构建 ====================

    /**
     * 构建 OpenAI 兼容的请求参数
     */
    public OpenAiChatRequestParameters buildChatRequestParameters(ChatRequest request, ModelTarget target) {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder();

        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            builder.maxOutputTokens(request.getMaxTokens());
        }

        // 注入 reasoning_effort 标准参数
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        String modelName = target.candidate().getModel();
        if (reasoningRouter != null && thinkingLevel > 0) {
            Map<String, Object> reasoningParams = reasoningRouter.route(modelName, thinkingLevel);
            if (!reasoningParams.isEmpty()) {
                if (AiLogHolder.isEnabled()) {
                    AiLogHolder.log(String.valueOf(System.currentTimeMillis()),
                            "[REQ] Model: " + modelName + " | ThinkingLevel: " + thinkingLevel
                                    + " | Params: " + reasoningParams + "\n");
                }
                Object reasoningEffort = reasoningParams.get("reasoning_effort");
                if (reasoningEffort instanceof String effort && !effort.isEmpty()) {
                    builder.reasoningEffort(effort);
                }
            }
        }

        return builder.build();
    }

    /**
     * 构建 LangChain4j 的 ChatRequest（含 messages + parameters）
     */
    public dev.langchain4j.model.chat.request.ChatRequest buildLangChainChatRequest(ChatRequest request, ModelTarget target) {
        List<dev.langchain4j.data.message.ChatMessage> messages = toMessages(request);
        OpenAiChatRequestParameters params = buildChatRequestParameters(request, target);

        return dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(messages)
                .parameters(params)
                .build();
    }

    /**
     * 获取思考参数的原始 Map（供 DeepSeek 等需要原始 OkHttp 路径使用）
     */
    public Map<String, Object> resolveReasoningParams(ChatRequest request, ModelTarget target) {
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        if (reasoningRouter == null || thinkingLevel <= 0) return Map.of();
        return reasoningRouter.route(target.candidate().getModel(), thinkingLevel);
    }

    // ==================== 原始请求体构建（供 OkHttp 路径使用） ====================

    public Map<String, Object> buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        Map<String, Object> reasoningParams = reasoningRouter != null
                ? reasoningRouter.route(target.candidate().getModel(), thinkingLevel)
                : Map.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.candidate().getModel());
        body.put("stream", stream);
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        if (request.getMaxTokens() != null) body.put("max_tokens", request.getMaxTokens());
        if (!reasoningParams.isEmpty()) body.putAll(reasoningParams);

        List<Map<String, Object>> msgs = new ArrayList<>();
        if (request.getMessages() != null) {
            for (ChatMessage msg : request.getMessages()) {
                Map<String, Object> m = new LinkedHashMap<>();
                String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user";
                m.put("role", role);
                List<String> imageUrls = msg.getImageUrls();
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    List<Map<String, Object>> contentArray = new ArrayList<>();
                    contentArray.add(Map.of("type", "text", "text",
                            msg.getContent() != null ? msg.getContent() : ""));
                    for (String url : imageUrls) {
                        String dataUri = resolveImageDataUri(url);
                        if (dataUri != null && !dataUri.isEmpty() && !dataUri.equals(url)) {
                            contentArray.add(Map.of("type", "image_url",
                                    "image_url", Map.of("url", dataUri)));
                        }
                    }
                    m.put("content", contentArray);
                } else {
                    m.put("content", msg.getContent() != null ? msg.getContent() : "");
                }
                msgs.add(m);
            }
        }
        body.put("messages", msgs);
        return body;
    }

    public Map<String, Object> buildRequestBody(ChatRequest request, ModelTarget target) {
        return buildRequestBody(request, target, false);
    }

    // ==================== 缓存管理 ====================

    public void evict(String modelId) {
        if (modelId == null) return;
        ChatModel removedSync = syncChatCache.remove(modelId);
        StreamingChatModel removedStream = streamChatCache.remove(modelId);
        EmbeddingModel removedEmbedding = embeddingCache.remove(modelId);
        closeQuietly(removedSync, modelId);
        closeQuietly(removedStream, modelId);
        closeQuietly(removedEmbedding, modelId);
        log.debug("已清除模型缓存: modelId={}", modelId);
    }

    public void evictAll() {
        syncChatCache.forEach((id, model) -> closeQuietly(model, id));
        syncChatCache.clear();
        streamChatCache.forEach((id, model) -> closeQuietly(model, id));
        streamChatCache.clear();
        embeddingCache.forEach((id, model) -> closeQuietly(model, id));
        embeddingCache.clear();
        log.info("已清除所有模型缓存");
    }

    private void closeQuietly(Object model, String modelId) {
        if (model == null) return;
        if (model instanceof AutoCloseable closeable) {
            try {
                closeable.close();
                log.debug("已关闭模型资源: modelId={}, class={}",
                        modelId, model.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("关闭模型资源失败: modelId={}, class={}",
                        modelId, model.getClass().getSimpleName(), e);
            }
        }
    }

    private <V> void evictIfNecessary(Map<String, V> cache) {
        if (cache.size() <= MAX_CACHE_SIZE) return;
        int toEvict = cache.size() - MAX_CACHE_SIZE / 2;
        var it = cache.keySet().iterator();
        while (it.hasNext() && toEvict > 0) {
            String key = it.next();
            V removed = cache.remove(key);
            if (removed instanceof AutoCloseable) {
                closeQuietly(removed, key);
            }
            toEvict--;
        }
        log.info("缓存驱逐完成，当前缓存大小: {}", cache.size());
    }

    // ==================== S3 图片编码 ====================

    private String resolveImageDataUri(String url) {
        if (url == null) return "";
        try {
            if (!url.startsWith("s3://")) {
                return url;
            }
            String path = url.substring(5);
            int slashIdx = path.indexOf('/');
            if (slashIdx < 0) return url;
            String bucket = path.substring(0, slashIdx);
            String key = path.substring(slashIdx + 1);

            byte[] imageBytes = s3Client.getObject(b -> b.bucket(bucket).key(key)).readAllBytes();
            String mimeType = detectMimeType(key);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return "data:" + mimeType + ";base64," + base64;
        } catch (Exception e) {
            log.warn("读取 S3 图片失败，回退到原始 URL: {}", url, e);
            return url;
        }
    }

    private String detectMimeType(String key) {
        if (key == null) return "image/jpeg";
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
