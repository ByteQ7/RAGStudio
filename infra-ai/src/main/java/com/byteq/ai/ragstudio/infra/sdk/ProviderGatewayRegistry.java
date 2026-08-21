package com.byteq.ai.ragstudio.infra.sdk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型供应商网关注册表
 * <p>
 * 大模型调用统一收敛为<b>三种策略</b>，按优先级解析：
 * <pre>
 * ① 官方 SDK 方案（supports 命中厂商名 + 协议）：
 *      protocol=dashscope → DashScopeGateway（dashscope-sdk-java 原生接口）
 *      zhipu+openai      → ZhipuGateway（zai-sdk）
 *      volcengine+openai → VolcEngineGateway（ark-runtime）
 * ② Anthropic 兼容 → AnthropicGateway（anthropic-java，baseUrl 可配 → 承载 DeepSeek 等）
 * ③ OpenAI 兼容（最终兜底）→ OpenAiGateway（openai-java，baseUrl 可配 → 承载 DeepSeek/SiliconFlow 等）
 * </pre>
 * 已移除手写 HTTP 协议兜底（OpenAiCompatibleGateway），模型调用不再直接拼接裸 HTTP。
 * </p>
 */
@Slf4j
@Component
public class ProviderGatewayRegistry {

    /** 专属网关列表（OpenAiGateway 作为最终兜底，不参与主动匹配） */
    private final List<ProviderGateway> gateways;

    /** OpenAI 兼容兜底网关（openai-java） */
    private final OpenAiGateway openAiGateway;

    public ProviderGatewayRegistry(List<ProviderGateway> gateways, OpenAiGateway openAiGateway) {
        this.gateways = gateways.stream()
                .filter(g -> g != openAiGateway)
                .toList();
        this.openAiGateway = openAiGateway;
    }

    /**
     * 解析厂商 + 协议对应的网关。
     *
     * @param providerName 厂商名
     * @param protocolName 协议名（可空，空视为 openai）
     * @return 匹配到的网关，永不返回 null（最终回落到 {@link OpenAiGateway}）
     */
    public ProviderGateway resolve(String providerName, String protocolName) {
        String protocol = normalizeProtocol(protocolName);
        for (ProviderGateway gateway : gateways) {
            if (gateway.supports(providerName, protocol)) {
                return gateway;
            }
        }
        log.debug("无专属网关命中，回落到 OpenAiGateway(openai-java): provider={}, protocol={}", providerName, protocol);
        return openAiGateway;
    }

    private String normalizeProtocol(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return "openai";
        }
        return protocolName.trim().toLowerCase();
    }
}