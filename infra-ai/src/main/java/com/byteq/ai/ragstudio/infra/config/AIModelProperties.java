package com.byteq.ai.ragstudio.infra.config;

import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型配置属性类
 * <p>
 * 通过 Spring Boot 的 {@link ConfigurationProperties} 机制从配置文件（如 application.yml）
 * 中读取以 "ai" 为前缀的 AI 相关配置信息。该配置类是 AI 模型调用的核心配置入口，
 * 统一管理所有模型提供商、模型组、选择策略和流式响应等配置。
 * </p>
 *
 * <p>配置文件结构（application.yml）：</p>
 * <pre>
 * ai:
 *   providers:       # 模型提供商配置
 *     siliconflow:
 *       url: https://api.siliconflow.cn
 *       api-key: ${SILICONFLOW_API_KEY}
 *       endpoints:
 *         chat: /v1/chat/completions
 *         embedding: /v1/embeddings
 *   chat:            # 聊天模型组
 *     default-model: deepseek-chat
 *     candidates:
 *       - id: deepseek-chat
 *         provider: siliconflow
 *         model: deepseek-ai/DeepSeek-V2.5
 *   selection:
 *     failure-threshold: 2
 *     open-duration-ms: 30000
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai")
public class AIModelProperties {

    /**
     * AI 模型提供商配置映射
     * <p>
     * key 为提供商标识符（如 "siliconflow"、"bailian"），
     * value 为对应的提供商连接信息，包括基础 URL、API 密钥和端点映射。
     * 配置中的 key 应与 {@link ModelProvider} 中的 ID 对应。
     * </p>
     */
    private Map<String, ProviderConfig> providers = new HashMap<>();

    /**
     * 聊天对话模型组配置
     * <p>
     * 配置用于聊天对话场景的模型列表，包括默认模型、深度思考模型和候选模型集合。
     * 对应 {@link ModelCapability#CHAT} 能力类型的模型组。
     * </p>
     */
    private ModelGroup chat = new ModelGroup();

    /**
     * 向量嵌入模型组配置
     * <p>
     * 配置用于向量嵌入场景的模型列表，包括默认模型和候选模型集合。
     * 对应 {@link ModelCapability#EMBEDDING} 能力类型的模型组。
     * </p>
     */
    private ModelGroup embedding = new ModelGroup();

    /**
     * 重排序模型组配置
     * <p>
     * 配置用于重排序场景的模型列表，包括默认模型和候选模型集合。
     * 对应 {@link ModelCapability#RERANK} 能力类型的模型组。
     * </p>
     */
    private ModelGroup rerank = new ModelGroup();

    /**
     * 模型选择策略配置
     * <p>
     * 配置模型选择的故障转移和熔断策略，当默认模型不可用时自动切换到备用模型，
     * 以及在连续失败达到阈值时触发熔断保护。
     * </p>
     */
    private Selection selection = new Selection();

    /**
     * 流式响应配置
     * <p>
     * 配置流式 SSE（Server-Sent Events）响应的相关参数，
     * 如消息分块大小等，用于优化流式输出的性能表现。
     * </p>
     */
    private Stream stream = new Stream();

    /**
     * 模型组配置类
     * <p>
     * 定义了一组具有相同能力类型（如聊天、嵌入、重排序）的模型集合。
     * 包含默认模型、深度思考模型以及候选模型列表，支持按优先级和可用性进行模型选择与故障转移。
     * </p>
     */
    @Data
    public static class ModelGroup {
        /**
         * 默认使用的模型标识
         * <p>
         * 对应 {@link ModelCandidate#id} 字段，指定在未明确选择模型时默认使用的模型。
         * 该模型必须在 candidates 列表中存在。
         * </p>
         */
        private String defaultModel;

        /**
         * 深度思考模型标识
         * <p>
         * 专门用于需要深度推理能力的场景（如复杂逻辑推理、数学问题求解等），
         * 对应 {@link ModelCandidate#id} 字段。深度思考模型通常具有更强的推理能力但响应较慢。
         * </p>
         */
        private String deepThinkingModel;

        /**
         * 候选模型列表
         * <p>
         * 该模型组下所有可用的候选模型配置集合。
         * 模型选择时根据优先级（{@link ModelCandidate#priority}）和启用状态（{@link ModelCandidate#enabled}）
         * 进行排序和过滤，支持故障转移。
         * </p>
         */
        private List<ModelCandidate> candidates = new ArrayList<>();
    }

    /**
     * 模型候选配置类
     * <p>
     * 定义单个可用模型的具体配置信息，包括模型标识、所属提供商、模型名称、
     * 自定义 URL、向量维度以及调度相关属性（优先级、启用状态、思考链支持）。
     * </p>
     */
    @Data
    public static class ModelCandidate {

        /**
         * 模型唯一标识符
         * <p>
         * 用于在模型组中唯一标识该候选模型，配置中的 defaultModel 和 deepThinkingModel
         * 通过此 id 引用具体的模型实例。
         * </p>
         */
        private String id;

        /**
         * 模型提供商名称
         * <p>
         * 对应 {@link AIModelProperties#providers} 配置映射中的 key，
         * 用于关联到具体的提供商连接配置（URL、API 密钥、端点等）。
         * </p>
         */
        private String provider;

        /**
         * 模型名称
         * <p>
         * 在发起 API 请求时实际使用的模型标识名称，
         * 例如 "deepseek-ai/DeepSeek-V2.5"、"text-embedding-v2" 等。
         * </p>
         */
        private String model;

        /**
         * 模型访问 URL
         * <p>
         * 可选字段。如果配置了该 URL，将优先使用此 URL 发起模型请求，
         * 而不是从提供商配置中拼接端点路径。适用于需要自定义 API 地址的场景。
         * </p>
         */
        private String url;

        /**
         * 向量维度
         * <p>
         * 仅对 embedding（向量嵌入）模型有效，指定生成的向量表示的维度大小。
         * 用于在向量数据库中建索引时指定维度参数。
         * </p>
         */
        private Integer dimension;

        /**
         * 模型优先级
         * <p>
         * 数值越小优先级越高，在多个候选模型同时可用时按优先级排序选择。
         * 默认值为 100，建议配置范围 1~1000。
         * </p>
         */
        private Integer priority = 100;

        /**
         * 是否启用该模型
         * <p>
         * 如果设为 false，该模型将被跳过，不会参与模型选择。
         * 用于在不删除配置的情况下临时禁用某个模型。
         * </p>
         */
        private Boolean enabled = true;

        /**
         * 是否支持思考链（Chain of Thought）功能
         * <p>
         * 标识模型是否支持深度思考/推理能力，如 DeepSeek-R1 等具有思维链能力的模型。
         * 在需要复杂推理的场景中，优先选择 supportsThinking 为 true 的模型。
         * </p>
         */
        private Boolean supportsThinking = false;
    }

    /**
     * 提供商配置类
     * <p>
     * 定义模型提供商的基本连接信息，包括 API 地址、认证密钥以及各类模型能力的端点映射。
     * 一个提供商可以提供多种模型能力（如聊天、嵌入、重排序），通过 endpoints 映射区分。
     * </p>
     */
    @Data
    public static class ProviderConfig {

        /**
         * 提供商 API 基础 URL
         * <p>
         * 例如 "https://api.siliconflow.cn" 或 "https://dashscope.aliyuncs.com"。
         * 与 endpoints 中的路径拼接后构成完整的 API 请求地址。
         * </p>
         */
        private String url;

        /**
         * API 密钥
         * <p>
         * 用于调用提供商 API 的身份认证密钥。
         * 建议通过环境变量或外部密钥管理服务注入，避免明文存储在配置文件中。
         * </p>
         */
        private String apiKey;

        /**
         * 端点路径映射配置
         * <p>
         * key 为模型能力类型（如 "chat"、"embedding"、"rerank"），
         * value 为对应的 API 端点路径（如 "/v1/chat/completions"）。
         * 与 {@link #url} 拼接后构成完整的请求 URL。
         * </p>
         */
        private Map<String, String> endpoints = new HashMap<>();
    }

    /**
     * 模型选择策略配置类
     * <p>
     * 配置模型调用过程中的故障转移（Failover）和熔断（Circuit Breaker）策略。
     * 当当前模型调用连续失败达到阈值时，触发熔断保护，暂停使用该模型一段时间，
     * 并自动切换到备用模型，提高系统的可用性和稳定性。
     * </p>
     */
    @Data
    public static class Selection {

        /**
         * 失败阈值
         * <p>
         * 连续调用失败的次数达到该值后，触发熔断机制。
         * 默认值为 2，即连续 2 次失败后熔断。
         * </p>
         */
        private Integer failureThreshold = 2;

        /**
         * 熔断器打开持续时间（毫秒）
         * <p>
         * 熔断触发后，在该时间范围内不再尝试调用失败模型。
         * 超过该时间后，熔断器自动半开，允许试探性恢复调用。
         * 默认值为 30000 毫秒（30 秒）。
         * </p>
         */
        private Long openDurationMs = 30000L;
    }

    /**
     * 流式响应配置类
     * <p>
     * 配置 AI 模型流式响应（SSE - Server-Sent Events）的相关参数。
     * 流式响应允许模型逐步输出结果，提升用户感知的响应速度。
     * </p>
     */
    @Data
    public static class Stream {

        /**
         * 消息分块大小
         * <p>
         * 在流式响应中，每次向客户端发送的消息块中包含的 Token 数量。
         * 该值影响流式输出的平滑度和实时性，值越小推送越频繁但网络开销越大。
         * 默认值为 5，表示每累积 5 个 Token 推送一次。
         * </p>
         */
        private Integer messageChunkSize = 5;
    }
}
