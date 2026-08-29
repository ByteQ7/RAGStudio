package com.byteq.ai.ragstudio.infra.agentscope;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.chat.StructuredOutputs;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.reasoning.ReasoningRouter;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * AgentScope 模型工厂
 * <p>
 * 根据数据库动态配置（供应商 / 模型 / 协议）构建 AgentScope {@link Model} 实例，
 * 并负责将项目统一的 {@link ChatRequest} 转换为 AgentScope 的消息与生成参数。
 * 替换自研 OkHttp 协议层（ModelHttpClient / ModelProtocol）成为模型调用入口。
 * </p>
 */
@Slf4j
@Component
public class AgentScopeModelFactory {

    /** 传输层：JDK HttpClient 实现（规避项目 okhttp 4.x 与 AgentScope 5.x 版本冲突） */
    private static final HttpTransport JDK_TRANSPORT = JdkHttpTransport.builder().build();

    /** OpenAI 兼容协议默认 Chat 端点 */
    private static final String DEFAULT_CHAT_ENDPOINT = "/v1/chat/completions";

    private final HttpModelFactory httpModelFactory;
    private final ReasoningRouter reasoningRouter;

    public AgentScopeModelFactory(HttpModelFactory httpModelFactory, ReasoningRouter reasoningRouter) {
        this.httpModelFactory = httpModelFactory;
        this.reasoningRouter = reasoningRouter;
    }

    /**
     * 根据模型目标构建 AgentScope Chat Model
     * <p>
     * 协议映射：openai / deepseek / siliconflow 等 → OpenAIChatModel（OpenAI 兼容协议）；
     * dashscope → DashScopeChatModel（百炼原生协议）；anthropic → AnthropicChatModel。
     * </p>
     */
    public Model buildChatModel(ModelTarget target) {
        String protocol = target.protocolName();
        String apiKey = target.provider().getApiKey();
        String modelName = target.candidate().getModel();
        String baseUrl = resolveBaseUrl(target);

        if (StringUtils.hasText(apiKey)) {
            return switch (protocol) {
                case "dashscope" -> buildDashScopeModel(modelName, apiKey, baseUrl);
                case "anthropic" -> buildAnthropicModel(modelName, apiKey, baseUrl);
                default -> buildOpenAiModel(modelName, apiKey, baseUrl, target);
            };
        }

        // 未配置 API Key（连通性探测等场景），退回构建不带 Key 的模型，由调用方感知鉴权失败
        return buildOpenAiModel(modelName, null, baseUrl, target);
    }

    private Model buildOpenAiModel(String modelName, String apiKey, String baseUrl, ModelTarget target) {
        String endpointPath = resolveEndpointPath(target, "chat");
        return OpenAIChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .endpointPath(endpointPath)
                .httpTransport(JDK_TRANSPORT)
                .build();
    }

    private Model buildDashScopeModel(String modelName, String apiKey, String baseUrl) {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .httpTransport(JDK_TRANSPORT)
                .build();
    }

    private Model buildAnthropicModel(String modelName, String apiKey, String baseUrl) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 构建生成参数（含推理深度适配与响应格式约束）
     */
    public GenerateOptions buildOptions(ChatRequest request, ModelTarget target, boolean stream) {
        GenerateOptions.Builder builder = GenerateOptions.builder()
                .stream(stream)
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .maxTokens(request.getMaxTokens());

        // 响应格式约束：按模型能力下发 json_schema / json_object，
        // 未标记能力时不下发（AgentScope ReACT 循环可在模型管理中为模型开启 JSON Output）
        StructuredOutputs.Spec spec = StructuredOutputs.resolve(request, target);
        switch (spec.mode()) {
            case JSON_SCHEMA -> builder.responseFormat(ResponseFormat.jsonSchema(
                    JsonSchema.builder()
                            .name(spec.name())
                            .schema(spec.schema())
                            .strict(spec.strict())
                            .build()));
            case JSON_OBJECT -> builder.responseFormat(ResponseFormat.jsonObject());
            case NONE -> {
                if (StringUtils.hasText(request.getResponseFormat())
                        && !"json_object".equals(request.getResponseFormat())) {
                    builder.responseFormat(ResponseFormat.text());
                }
            }
        }

        // 推理深度：将 ReasoningRouter 产出的各模型原生参数映射到 GenerateOptions
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        if (thinkingLevel > 0 && reasoningRouter != null) {
            Map<String, Object> reasoningParams =
                    reasoningRouter.route(target.candidate().getModel(), thinkingLevel);
            applyReasoningParams(builder, reasoningParams);
        } else if (thinkingLevel <= 0 && reasoningRouter != null
                && reasoningRouter.usesThinkingSwitch(target.candidate().getModel())) {
            // DeepSeek 官方文档：思考模式默认开启；非思考模式需显式关闭，
            // 否则模型默认思考，思维链与成本不可控
            builder.additionalBodyParam("thinking", Map.of("type", "disabled"));
        }

        return builder.build();
    }

    /**
     * 推理参数映射：
     * reasoning_effort → GenerateOptions.reasoningEffort；budget_tokens → thinkingBudget；
     * 其余参数原样注入请求体。
     */
    private void applyReasoningParams(GenerateOptions.Builder builder, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "reasoning_effort" -> builder.reasoningEffort(String.valueOf(value));
                case "budget_tokens" -> {
                    if (value instanceof Number number) {
                        builder.thinkingBudget(number.intValue());
                    }
                }
                default -> builder.additionalBodyParam(key, value);
            }
        }
    }

    /**
     * 将项目统一消息列表转换为 AgentScope 消息列表
     * <p>
     * OBSERVATION 角色映射为 system 文本消息（与旧协议行为一致，避免模型将工具反馈
     * 误认为用户发言）；USER 消息的 S3 / data URI 图片转换为 ImageBlock。
     * </p>
     */
    public List<Msg> convertMessages(ChatRequest request) {
        List<Msg> msgs = new ArrayList<>();
        if (request.getMessages() == null) {
            return msgs;
        }
        for (ChatMessage msg : request.getMessages()) {
            if (msg.getRole() == null || msg.getRole() == ChatMessage.Role.USER) {
                msgs.add(buildUserMessage(msg));
            } else if (msg.getRole() == ChatMessage.Role.SYSTEM) {
                msgs.add(SystemMessage.builder()
                        .content(TextBlock.builder().text(msg.getContent() != null ? msg.getContent() : "").build())
                        .build());
            } else if (msg.getRole() == ChatMessage.Role.ASSISTANT) {
                msgs.add(buildAssistantMessage(msg));
            } else {
                // OBSERVATION 及其他角色 → system 文本（保持旧协议语义）
                msgs.add(SystemMessage.builder()
                        .content(TextBlock.builder().text(msg.getContent() != null ? msg.getContent() : "").build())
                        .build());
            }
        }
        return msgs;
    }

    private Msg buildUserMessage(ChatMessage msg) {
        List<ContentBlock> blocks = new ArrayList<>();
        String text = msg.getContent() != null ? msg.getContent() : "";
        if (StringUtils.hasText(text)) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        if (msg.getImageUrls() != null) {
            for (String url : msg.getImageUrls()) {
                ImageBlock image = toImageBlock(url);
                if (image != null) {
                    blocks.add(image);
                }
            }
        }
        return UserMessage.builder().content(blocks).build();
    }

    private Msg buildAssistantMessage(ChatMessage msg) {
        AssistantMessage.Builder builder = AssistantMessage.builder();
        if (StringUtils.hasText(msg.getThinkingContent())) {
            // DeepSeek 官方文档：带 tools 的请求必须完整回传 reasoning_content，否则 400。
            // 思考模式下历史 assistant 消息需回灌思维链（非思考模式 thinkingContent 恒为空，不受影响）
            builder.content(ThinkingBlock.builder().thinking(msg.getThinkingContent()).build());
        }
        String text = msg.getContent() != null ? msg.getContent() : "";
        if (StringUtils.hasText(text)) {
            builder.content(TextBlock.builder().text(text).build());
        }
        return builder.build();
    }

    /**
     * 将 S3 URL / data URI / http(s) URL 图片转换为 AgentScope ImageBlock
     * <p>
     * s3:// → data URI → Base64Source；http(s) URL → URLSource（与旧协议直传 URL 行为一致）；
     * data URI → Base64Source。无法识别时返回 null（跳过该图片）。
     * </p>
     */
    public ImageBlock toImageBlock(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return ImageBlock.builder()
                        .source(URLSource.builder().url(url).build())
                        .build();
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
            return ImageBlock.builder()
                    .source(Base64Source.builder().mediaType(mediaType).data(data).build())
                    .build();
        } catch (Exception e) {
            log.warn("图片转 ImageBlock 失败，跳过: {}", url, e);
            return null;
        }
    }

    // ==================== URL / 端点解析 ====================

    private String resolveBaseUrl(ModelTarget target) {
        DynamicModelConfig.ModelEntry candidate = target.candidate();
        DynamicModelConfig.ProviderEntry provider = target.provider();
        if (candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl().replaceAll("/+$", "");
        }
        if (provider != null && provider.getUrl() != null && !provider.getUrl().isBlank()) {
            return provider.getUrl().replaceAll("/+$", "");
        }
        return null;
    }

    private String resolveEndpointPath(ModelTarget target, String endpointKey) {
        DynamicModelConfig.ProviderEntry provider = target.provider();
        Map<String, String> endpoints = provider != null ? provider.getEndpoints() : null;
        if (endpoints != null) {
            String path = endpoints.get(endpointKey);
            if (path != null && !path.isBlank()) {
                return path;
            }
        }
        return DEFAULT_CHAT_ENDPOINT;
    }
}
