package com.byteq.ai.ragstudio.infra.springai;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.reasoning.ReasoningRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import software.amazon.awssdk.services.s3.S3Client;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring AI ChatModel / EmbeddingModel 工厂
 * <p>
 * 根据数据库配置动态创建 Spring AI 模型实例，
 * 替代原有的 OkHttp 手工 HTTP 调用。
 * <p>
 * 模型实例按 modelId 缓存，避免重复创建底层 HTTP 连接。
 * <p>
 * 支持的提供商：
 * - OpenAI 兼容协议（DeepSeek / SiliconFlow / 百炼兼容模式）→ OpenAiChatModel
 */
@Slf4j
@Component
public class SpringAiChatModelFactory {

    /**
     * 缓存上限，超过后驱逐最早的条目，防止无界增长导致文件描述符和内存泄漏
     */
    private static final int MAX_CACHE_SIZE = 64;

    private final S3Client s3Client;
    private final ReasoningRouter reasoningRouter;

    public ReasoningRouter getReasoningRouter() {
        return reasoningRouter;
    }

    /**
     * 构建完整的 API 请求体（绕过 Spring AI 的 extraBody 序列化问题）
     */
    public Map<String, Object> buildRequestBody(
            com.byteq.ai.ragstudio.framework.convention.ChatRequest request, ModelTarget target) {
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        Map<String, Object> reasoningParams = reasoningRouter.route(
                target.candidate().getModel(), thinkingLevel);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", target.candidate().getModel());
        body.put("stream", false);
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        if (request.getMaxTokens() != null) body.put("max_tokens", request.getMaxTokens());
        if (!reasoningParams.isEmpty()) body.putAll(reasoningParams);

        // messages
        java.util.List<Map<String, Object>> msgs = new java.util.ArrayList<>();
        if (request.getMessages() != null) {
            for (com.byteq.ai.ragstudio.framework.convention.ChatMessage msg : request.getMessages()) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user";
                m.put("role", role);
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                msgs.add(m);
            }
        }
        body.put("messages", msgs);
        return body;
    }

    public SpringAiChatModelFactory(S3Client s3Client, ReasoningRouter reasoningRouter) {
        this.s3Client = s3Client;
        this.reasoningRouter = reasoningRouter;
    }

    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 ChatModel 实例
     *
     * @param target 模型目标配置（包含候选模型和提供商信息）
     * @return Spring AI ChatModel
     */
    public ChatModel getOrCreateChatModel(ModelTarget target) {
        evictIfNecessary(chatModelCache);
        return chatModelCache.computeIfAbsent(target.id(), id -> createChatModel(target));
    }

    /**
     * 获取或创建 EmbeddingModel 实例
     *
     * @param target 模型目标配置
     * @return Spring AI EmbeddingModel
     */
    public EmbeddingModel getOrCreateEmbeddingModel(ModelTarget target) {
        evictIfNecessary(embeddingModelCache);
        return embeddingModelCache.computeIfAbsent(target.id(), id -> createEmbeddingModel(target));
    }

    /**
     * 清除指定模型的缓存实例
     * <p>
     * 当供应商配置（API Key、URL 等）或模型配置发生变更时调用，
     * 使下次调用时使用新配置重新创建实例。
     * </p>
     *
     * @param modelId 模型标识
     */
    public void evict(String modelId) {
        if (modelId == null) return;
        ChatModel removedChat = chatModelCache.remove(modelId);
        EmbeddingModel removedEmbedding = embeddingModelCache.remove(modelId);
        closeQuietly(removedChat, modelId);
        closeQuietly(removedEmbedding, modelId);
        log.debug("已清除模型缓存: modelId={}", modelId);
    }

    /**
     * 尝试释放模型底层 HTTP 连接池资源。
     * <p>
     * Spring AI 1.1.2 的 ChatModel / EmbeddingModel 未统一实现 AutoCloseable，
     * 此方法通过 instanceof 检测和反射尽力关闭底层 API 对象持有的连接池
     * （如 OpenAiApi 的 OkHttpClient）。
     */
    private void closeQuietly(Object model, String modelId) {
        if (model == null) return;
        // 1) 如果模型本身实现了 AutoCloseable，直接关闭
        if (model instanceof AutoCloseable closeable) {
            try {
                closeable.close();
                log.info("已关闭 AutoCloseable 模型: modelId={}, class={}", modelId, model.getClass().getSimpleName());
            } catch (Exception e) {
                log.warn("关闭模型资源失败: modelId={}, class={}", modelId, model.getClass().getSimpleName(), e);
            }
            return;
        }
        // 2) 反射尝试获取并关闭底层 API 对象
        try {
            java.lang.reflect.Field apiField = findField(model.getClass(), "openAiApi");
            if (apiField != null) {
                apiField.setAccessible(true);
                Object apiObj = apiField.get(model);
                if (apiObj instanceof AutoCloseable closeable) {
                    closeable.close();
                    log.info("已关闭模型底层 API 资源: modelId={}, apiClass={}", modelId, apiObj.getClass().getSimpleName());
                    return;
                }
                // OpenAiApi 内部持有 OkHttpClient，尝试关闭
                if (apiObj != null) {
                    java.lang.reflect.Field clientField = findField(apiObj.getClass(), "restClient", "webClient", "okHttpClient");
                    if (clientField != null) {
                        log.warn("模型底层 API 未实现 AutoCloseable，可能存在连接池泄漏: modelId={}, apiClass={}. " +
                                "建议升级 Spring AI 版本以获得更好的资源管理支持。", modelId, apiObj.getClass().getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("反射关闭模型资源时出错: modelId={}", modelId, e);
        }
        log.warn("模型未实现 AutoCloseable，底层 HTTP 连接池可能未被释放: modelId={}, class={}",
                modelId, model.getClass().getSimpleName());
    }

    // 在指定类中按名称列表查找第一个存在的字段，用于反射关闭底层资源
    private java.lang.reflect.Field findField(Class<?> clazz, String... fieldNames) {
        for (String name : fieldNames) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try next
            }
        }
        return null;
    }

    // ==================== 缓存驱逐 ====================

    /**
     * 当缓存超过上限时，逐出最早的条目（按迭代顺序），
     * 将缓存大小降至 MAX_CACHE_SIZE / 2 以留出空间。
     */
    private <V> void evictIfNecessary(Map<String, V> cache) {
        if (cache.size() <= MAX_CACHE_SIZE) return;
        int toEvict = cache.size() - MAX_CACHE_SIZE / 2;
        var it = cache.keySet().iterator();
        while (it.hasNext() && toEvict > 0) {
            String key = it.next();
            it.remove();
            if (cache == chatModelCache) {
                closeQuietly(chatModelCache.remove(key), key);
            } else if (cache == embeddingModelCache) {
                closeQuietly(embeddingModelCache.remove(key), key);
            }
            toEvict--;
        }
        log.info("缓存驱逐完成，当前缓存大小: {}", cache.size());
    }

    /**
     * 清除所有缓存（应用关闭或全局刷新时调用）
     */
    public void evictAll() {
        chatModelCache.forEach((id, model) -> closeQuietly(model, id));
        chatModelCache.clear();
        embeddingModelCache.forEach((id, model) -> closeQuietly(model, id));
        embeddingModelCache.clear();
        log.info("已清除所有模型缓存");
    }

    // ==================== ChatModel 创建 ====================

    // 根据模型目标配置创建 OpenAI 兼容的 ChatModel 实例，设置基础 URL、API Key 和端点路径
    private ChatModel createChatModel(ModelTarget target) {
        String providerId = target.candidate().getProvider();
        String baseUrl = resolveBaseUrl(target);
        String apiKey = resolveApiKey(target);

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "no-key");

        String chatPath = resolveEndpointPath(target, "chat");
        if (chatPath != null) {
            apiBuilder.completionsPath(chatPath);
        }

        log.info("创建 OpenAI-compatible ChatModel: provider={}, model={}, baseUrl={}, completionsPath={}",
                providerId, target.candidate().getModel(), baseUrl, chatPath != null ? chatPath : "/v1/chat/completions");
        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .build();
    }

    // ==================== EmbeddingModel 创建 ====================

    // 根据模型目标配置创建 OpenAI 兼容的 EmbeddingModel 实例，支持自定义维度参数
    private EmbeddingModel createEmbeddingModel(ModelTarget target) {
        String providerId = target.candidate().getProvider();
        String baseUrl = resolveBaseUrl(target);
        String apiKey = resolveApiKey(target);
        String modelName = target.candidate().getModel();
        Integer dimension = target.candidate().getDimension();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "no-key")
                .build();
        log.info("创建 OpenAI-compatible EmbeddingModel: provider={}, model={}, dimension={}, baseUrl={}",
                providerId, modelName, dimension, baseUrl);

        OpenAiEmbeddingOptions.Builder optionsBuilder = OpenAiEmbeddingOptions.builder()
                .model(modelName);
        if (dimension != null && dimension > 0) {
            optionsBuilder.dimensions(dimension);
        }

        // OpenAiEmbeddingModel 没有 Builder，使用构造函数
        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                optionsBuilder.build());
    }

    // ==================== URL 解析 ====================

    /**
     * 解析基础 URL
     * <p>
     * 优先级：候选模型的自定义 URL > 提供商的基础 URL。
     * 对于 OpenAI 兼容提供商，Spring AI 会自动追加 /v1/chat/completions 等端点路径。
     */
    public String resolveBaseUrl(ModelTarget target) {
        DynamicModelConfig.ModelEntry candidate = target.candidate();
        DynamicModelConfig.ProviderEntry provider = target.provider();

        if (candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl().replaceAll("/+$", "");
        }
        return provider.getUrl().replaceAll("/+$", "");
    }

    // 从模型目标的提供商配置中提取 API Key
    public String resolveApiKey(ModelTarget target) {
        DynamicModelConfig.ProviderEntry provider = target.provider();
        return provider.getApiKey();
    }

    // 根据端点类型（如 chat、embedding）从提供商配置中解析对应的端点路径
    private String resolveEndpointPath(ModelTarget target, String endpointKey) {
        Map<String, String> endpoints = target.provider().getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) return null;
        String path = endpoints.get(endpointKey);
        return (path != null && !path.isBlank()) ? path : null;
    }

    // ==================== Options 构建 ====================

    /**
     * 根据请求参数构建 OpenAI ChatOptions
     * <p>
     * 包含模型名称和可选的生成参数（temperature / topP / topK / maxTokens）。
     *
     * @param target  模型目标配置（提供模型名称）
     * @param request 聊天请求（提供生成参数）
     * @return ChatOptions 实例
     */
    public ChatOptions buildChatOptions(ModelTarget target, com.byteq.ai.ragstudio.framework.convention.ChatRequest request) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(target.candidate().getModel());

        if (request.getTemperature() != null) builder.temperature(request.getTemperature());
        if (request.getTopP() != null) builder.topP(request.getTopP());
        if (request.getMaxTokens() != null) builder.maxTokens(request.getMaxTokens());

        // 深度思考参数路由（无论 level 是否为 0 都执行，0 时返回关闭参数）
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        String modelName = target.candidate().getModel();
        if (reasoningRouter != null) {
            Map<String, Object> reasoningParams = reasoningRouter.route(modelName, thinkingLevel);
            if (!reasoningParams.isEmpty()) {
                builder.extraBody(reasoningParams);
                if (AiLogHolder.isEnabled()) {
                    AiLogHolder.log(String.valueOf(System.currentTimeMillis()),
                            "[REQ] Model: " + modelName + " | ThinkingLevel: " + thinkingLevel + " | ExtraBody: " + reasoningParams + "\n");
                }
            }
        }

        return builder.build();
    }

    // ==================== Prompt 转换 ====================

    /**
     * 将项目的 ChatRequest 转换为 Spring AI 的 Prompt
     * <p>
     * 映射规则：
     * - SYSTEM → SystemMessage
     * - USER → UserMessage
     * - ASSISTANT → AssistantMessage
     *
     * @param request 项目内部的 ChatRequest
     * @param target  模型目标（用于构建 ChatOptions）
     * @return Spring AI Prompt
     */
    public Prompt toPrompt(com.byteq.ai.ragstudio.framework.convention.ChatRequest request, ModelTarget target) {
        List<Message> messages = request.getMessages().stream()
                .map(this::convertMessage)
                .toList();

        ChatOptions options = buildChatOptions(target, request);

        return new Prompt(messages, options);
    }

    // 将项目内部的 ChatMessage 转换为 Spring AI 的 Message，支持 SYSTEM/USER/ASSISTANT 角色映射
    // USER 消息若包含图片 URL，则从 S3 读取图片并编码为 data URI，
    // 以 Spring AI Media 对象的形式附加到 UserMessage，确保 LLM API 能收到结构化 image_url 内容
    private Message convertMessage(ChatMessage msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> new SystemMessage(msg.getContent());
            case USER -> {
                String text = msg.getContent() != null ? msg.getContent() : "";
                List<String> imageUrls = msg.getImageUrls();
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    List<Media> mediaList = new ArrayList<>();
                    for (String url : imageUrls) {
                        String dataUri = resolveImageDataUri(url);
                        if (dataUri != null && !dataUri.isEmpty() && !dataUri.equals(url)) {
                            // 成功转为 data URI → 作为 Media 对象附加
                            String mimeType = detectMimeTypeFromUri(dataUri);
                            mediaList.add(new Media(MimeTypeUtils.parseMimeType(mimeType), java.net.URI.create(dataUri)));
                        }
                    }
                    if (!mediaList.isEmpty()) {
                        yield UserMessage.builder()
                                .text(text)
                                .media(mediaList)
                                .build();
                    }
                }
                yield new UserMessage(text);
            }
            case ASSISTANT -> {
                if (msg.getThinkingContent() != null) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("reasoningContent", msg.getThinkingContent());
                    yield AssistantMessage.builder()
                            .content(msg.getContent() != null ? msg.getContent() : "")
                            .properties(metadata)
                            .build();
                }
                yield new AssistantMessage(
                        msg.getContent() != null ? msg.getContent() : "");
            }
        };
    }

    // 从 data URI 中提取 MIME 类型
    private String detectMimeTypeFromUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:")) return "image/jpeg";
        int semi = dataUri.indexOf(';');
        if (semi < 0) return "image/jpeg";
        return dataUri.substring(5, semi);
    }

    // 将图片 URL 转换为 data URI（base64 编码），确保 LLM 能直接读取
    // 支持格式: s3://bucket/key。已转换为 HTTP/HTTPS 的 URL 直接返回（如预签名 URL）
    private String resolveImageDataUri(String url) {
        if (url == null) return "";
        try {
            if (!url.startsWith("s3://")) {
                // 非 s3:// URL（如预签名 HTTP URL），直接使用
                return url;
            }

            // s3://bucket/key → 从 S3 读取图片并转为 data URI
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

    // 根据文件名后缀推断 MIME 类型
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
