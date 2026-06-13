package com.byteq.ai.ragstudio.infra.springai;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

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

    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 ChatModel 实例
     *
     * @param target 模型目标配置（包含候选模型和提供商信息）
     * @return Spring AI ChatModel
     */
    public ChatModel getOrCreateChatModel(ModelTarget target) {
        return chatModelCache.computeIfAbsent(target.id(), id -> createChatModel(target));
    }

    /**
     * 获取或创建 EmbeddingModel 实例
     *
     * @param target 模型目标配置
     * @return Spring AI EmbeddingModel
     */
    public EmbeddingModel getOrCreateEmbeddingModel(ModelTarget target) {
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

    // ==================== ChatModel 创建 ====================

    private ChatModel createChatModel(ModelTarget target) {
        String providerId = target.candidate().getProvider();
        String baseUrl = resolveBaseUrl(target);
        String apiKey = resolveApiKey(target);

        // 所有 OpenAI 兼容提供商统一使用 OpenAiChatModel
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "no-key")
                .build();
        log.info("创建 OpenAI-compatible ChatModel: provider={}, model={}, baseUrl={}",
                providerId, target.candidate().getModel(), baseUrl);
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .build();
    }

    // ==================== EmbeddingModel 创建 ====================

    private EmbeddingModel createEmbeddingModel(ModelTarget target) {
        String providerId = target.candidate().getProvider();
        String baseUrl = resolveBaseUrl(target);
        String apiKey = resolveApiKey(target);
        String modelName = target.candidate().getModel();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey != null ? apiKey : "no-key")
                .build();
        log.info("创建 OpenAI-compatible EmbeddingModel: provider={}, model={}, baseUrl={}",
                providerId, modelName, baseUrl);
        // OpenAiEmbeddingModel 没有 Builder，使用构造函数
        return new OpenAiEmbeddingModel(
                api,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(modelName)
                        .build());
    }

    // ==================== URL 解析 ====================

    /**
     * 解析基础 URL
     * <p>
     * 优先级：候选模型的自定义 URL > 提供商的基础 URL。
     * 对于 OpenAI 兼容提供商，Spring AI 会自动追加 /v1/chat/completions 等端点路径。
     */
    private String resolveBaseUrl(ModelTarget target) {
        DynamicModelConfig.ModelEntry candidate = target.candidate();
        DynamicModelConfig.ProviderEntry provider = target.provider();

        if (candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl().replaceAll("/+$", "");
        }
        return provider.getUrl().replaceAll("/+$", "");
    }

    private String resolveApiKey(ModelTarget target) {
        DynamicModelConfig.ProviderEntry provider = target.provider();
        return provider.getApiKey();
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

    private Message convertMessage(ChatMessage msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> new SystemMessage(msg.getContent());
            case USER -> new UserMessage(msg.getContent());
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
}
