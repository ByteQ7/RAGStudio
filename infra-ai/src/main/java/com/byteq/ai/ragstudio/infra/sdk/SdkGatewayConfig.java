package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.infra.chat.ChatClient;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingClient;
import com.byteq.ai.ragstudio.infra.rerank.RerankClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 网关能力 Bean 注册
 * <p>
 * 为各模型供应商注册基于 {@link ProviderGatewayRegistry} 的
 * {@link ChatClient} / {@link EmbeddingClient} / {@link RerankClient} 薄适配器 Bean，
 * 供路由层（RoutingLLMService / RoutingEmbeddingService / RoutingRerankService）
 * 按 provider 名收集与查找。每个 Bean 在调用期按 {@code ModelTarget} 的协议
 * 动态解析到具体 {@link ProviderGateway}。
 * </p>
 */
@Configuration
public class SdkGatewayConfig {

    /** 需要注册 ChatClient 的供应商列表 */
    private static final List<String> CHAT_PROVIDERS =
            List.of("bailian", "deepseek", "siliconflow", "zhipu", "volcengine", "openai", "anthropic");

    /** 需要注册 EmbeddingClient 的供应商列表 */
    private static final List<String> EMBEDDING_PROVIDERS =
            List.of("bailian", "siliconflow", "zhipu", "volcengine", "openai");

    /** 需要注册 RerankClient 的供应商列表（含 OpenAI 兼容 rerank 的厂商） */
    private static final List<String> RERANK_PROVIDERS =
            List.of("bailian", "siliconflow", "deepseek", "volcengine", "zhipu", "openai");

    @Bean
    public ChatClient bailianChatClient(ProviderGatewayRegistry registry) {
        return new GatewayChatClient("bailian", registry);
    }

    @Bean
    public ChatClient deepseekChatClient(ProviderGatewayRegistry registry) {
        return new GatewayChatClient("deepseek", registry);
    }

    @Bean
    public ChatClient siliconflowChatClient(ProviderGatewayRegistry registry) {
        return new GatewayChatClient("siliconflow", registry);
    }

    @Bean
    public ChatClient zhipuChatClient(ProviderGatewayRegistry registry) {
        return new GatewayChatClient("zhipu", registry);
    }

    @Bean
    public ChatClient volcengineChatClient(ProviderGatewayRegistry registry) {
        return new GatewayChatClient("volcengine", registry);
    }

    @Bean
    public ChatClient openaiChatClient(ProviderGatewayRegistry registry) {
        return new GatewayChatClient("openai", registry);
    }

    @Bean
    public ChatClient anthropicChatClient(ProviderGatewayRegistry registry) {
        return new GatewayChatClient("anthropic", registry);
    }

    @Bean
    public EmbeddingClient bailianEmbeddingClient(ProviderGatewayRegistry registry) {
        return new GatewayEmbeddingClient("bailian", registry);
    }

    @Bean
    public EmbeddingClient siliconflowEmbeddingClient(ProviderGatewayRegistry registry) {
        return new GatewayEmbeddingClient("siliconflow", registry);
    }

    @Bean
    public EmbeddingClient zhipuEmbeddingClient(ProviderGatewayRegistry registry) {
        return new GatewayEmbeddingClient("zhipu", registry);
    }

    @Bean
    public EmbeddingClient volcengineEmbeddingClient(ProviderGatewayRegistry registry) {
        return new GatewayEmbeddingClient("volcengine", registry);
    }

    @Bean
    public EmbeddingClient openaiEmbeddingClient(ProviderGatewayRegistry registry) {
        return new GatewayEmbeddingClient("openai", registry);
    }

    @Bean
    public RerankClient bailianRerankClient(ProviderGatewayRegistry registry) {
        return new GatewayRerankClient("bailian", registry);
    }

    @Bean
    public RerankClient siliconflowRerankClient(ProviderGatewayRegistry registry) {
        return new GatewayRerankClient("siliconflow", registry);
    }

    @Bean
    public RerankClient deepseekRerankClient(ProviderGatewayRegistry registry) {
        return new GatewayRerankClient("deepseek", registry);
    }

    @Bean
    public RerankClient volcengineRerankClient(ProviderGatewayRegistry registry) {
        return new GatewayRerankClient("volcengine", registry);
    }

    @Bean
    public RerankClient zhipuRerankClient(ProviderGatewayRegistry registry) {
        return new GatewayRerankClient("zhipu", registry);
    }

    @Bean
    public RerankClient openaiRerankClient(ProviderGatewayRegistry registry) {
        return new GatewayRerankClient("openai", registry);
    }
}