package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关通用工具单元测试（纯逻辑，不依赖 Spring / 网络）。
 */
class SdkGatewaySupportTest {

    @Test
    void matchesAliasShouldMatchIgnoringCaseAndSeparators() {
        assertTrue(SdkGatewaySupport.matchesAlias("bailian", "bailian", "百炼", "阿里云", "alibaba", "dashscope"));
        assertTrue(SdkGatewaySupport.matchesAlias("  BaIlian  ", "bailian"));
        assertTrue(SdkGatewaySupport.matchesAlias("silicon-flow", "siliconflow"));
        assertTrue(SdkGatewaySupport.matchesAlias("zhipuai", "zhipu", "zhipuai", "智谱"));
        assertTrue(SdkGatewaySupport.matchesAlias("volcengine", "volcengine", "火山引擎"));
        assertFalse(SdkGatewaySupport.matchesAlias("deepseek", "bailian"));
        assertFalse(SdkGatewaySupport.matchesAlias(null, "bailian"));
    }

    @Test
    void normalizeDashScopeBaseUrlShouldAppendApiV1WhenMissing() {
        assertEquals("https://dashscope.aliyuncs.com/api/v1",
                SdkGatewaySupport.normalizeDashScopeBaseUrl("https://dashscope.aliyuncs.com"));
        assertEquals("https://dashscope.aliyuncs.com/api/v1",
                SdkGatewaySupport.normalizeDashScopeBaseUrl("https://dashscope.aliyuncs.com/"));
        // 已含 /api/v1 前缀时保持不变
        assertEquals("https://dashscope.aliyuncs.com/api/v1",
                SdkGatewaySupport.normalizeDashScopeBaseUrl("https://dashscope.aliyuncs.com/api/v1"));
    }

    @Test
    void resolveSdkBaseUrlShouldStripResourceSuffixFromEndpointPath() {
        DynamicModelConfig.ProviderEntry provider = DynamicModelConfig.ProviderEntry.builder()
                .name("bailian")
                .url("https://dashscope.aliyuncs.com")
                .endpoints(Map.of(
                        "chat", "/compatible-mode/v1/chat/completions",
                        "embedding", "/compatible-mode/v1/embeddings"))
                .build();
        DynamicModelConfig.ModelEntry candidate = DynamicModelConfig.ModelEntry.builder()
                .id("qwen-plus").provider("bailian").model("qwen-plus").build();
        ModelTarget target = new ModelTarget("qwen-plus", candidate, provider);

        // openai-java 会自动追加 /chat/completions，因此 SDK base 应去掉资源后缀
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                SdkGatewaySupport.resolveSdkBaseUrl(target, "chat"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                SdkGatewaySupport.resolveSdkBaseUrl(target, "embedding"));
    }

    @Test
    void resolveSdkBaseUrlShouldHandleBaseUrlAlreadyPrefixed() {
        DynamicModelConfig.ProviderEntry provider = DynamicModelConfig.ProviderEntry.builder()
                .name("deepseek")
                .url("https://api.deepseek.com/v1")
                .endpoints(Map.of("chat", "/chat/completions"))
                .build();
        DynamicModelConfig.ModelEntry candidate = DynamicModelConfig.ModelEntry.builder()
                .id("deepseek-chat").provider("deepseek").model("deepseek-chat").build();
        ModelTarget target = new ModelTarget("deepseek-chat", candidate, provider);

        // baseUrl 已含 /v1，endpoints 只有资源路径 → 原样返回 baseUrl
        assertEquals("https://api.deepseek.com/v1", SdkGatewaySupport.resolveSdkBaseUrl(target, "chat"));
    }

    @Test
    void resolveSdkBaseUrlShouldHandleAnthropicMessagesPath() {
        DynamicModelConfig.ProviderEntry provider = DynamicModelConfig.ProviderEntry.builder()
                .name("deepseek")
                .url("https://api.deepseek.com/anthropic")
                .endpoints(Map.of("chat", "/v1/messages"))
                .build();
        DynamicModelConfig.ModelEntry candidate = DynamicModelConfig.ModelEntry.builder()
                .id("deepseek-chat").provider("deepseek").model("deepseek-chat").build();
        ModelTarget target = new ModelTarget("deepseek-chat", candidate, provider);

        // anthropic-java 会自动追加 /v1/messages → base 保持 host 级
        assertEquals("https://api.deepseek.com/anthropic", SdkGatewaySupport.resolveSdkBaseUrl(target, "chat"));
    }

    @Test
    void providerRegistryResolutionOrder() {
        // 无 Spring 环境下的解析顺序验证：构造与真实网关 supports 语义一致的轻量网关
        ProviderGateway dash = new ProbeGateway("dash", "bailian", List.of("dashscope"), true);
        // OpenAI / Anthropic 通用网关按协议匹配任意厂商（与真实实现一致）
        ProviderGateway openai = new ProbeGateway("openai", null, List.of("openai"), false);
        ProviderGateway anthropic = new ProbeGateway("anthropic", null, List.of("anthropic"), false);

        List<ProviderGateway> gateways = List.of(dash, openai, anthropic);

        assertEquals("dash", gateways.stream()
                .filter(g -> g.supports("bailian", "dashscope")).findFirst().orElse(openai).provider());
        assertEquals("openai", gateways.stream()
                .filter(g -> g.supports("deepseek", "openai")).findFirst().orElse(openai).provider());
        assertEquals("anthropic", gateways.stream()
                .filter(g -> g.supports("deepseek", "anthropic")).findFirst().orElse(openai).provider());
        // 专属网关未命中时（bailian 配 openai 协议）回落到通用 OpenAI 网关
        assertEquals("openai", gateways.stream()
                .filter(g -> g.supports("bailian", "openai")).findFirst().orElse(openai).provider());
    }

    /** 测试用轻量网关，仅验证 supports 解析顺序 */
    private static class ProbeGateway implements ProviderGateway {
        private final String id;
        private final String providerName;
        private final List<String> protocols;
        private final boolean matchProviderName;

        ProbeGateway(String id, String providerName, List<String> protocols, boolean matchProviderName) {
            this.id = id;
            this.providerName = providerName;
            this.protocols = protocols;
            this.matchProviderName = matchProviderName;
        }

        @Override
        public String provider() {
            return id;
        }

        @Override
        public boolean supports(String providerName, String protocolName) {
            if (!protocols.contains(protocolName)) {
                return false;
            }
            return !matchProviderName || this.providerName.equals(providerName);
        }

        @Override
        public String chat(com.byteq.ai.ragstudio.framework.convention.ChatRequest request, ModelTarget target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle streamChat(
                com.byteq.ai.ragstudio.framework.convention.ChatRequest request,
                com.byteq.ai.ragstudio.infra.chat.StreamCallback callback, ModelTarget target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.byteq.ai.ragstudio.framework.convention.RetrievedChunk> rerank(
                String query, List<com.byteq.ai.ragstudio.framework.convention.RetrievedChunk> candidates,
                int topN, ModelTarget target) {
            throw new UnsupportedOperationException();
        }
    }
}