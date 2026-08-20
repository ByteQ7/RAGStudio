package com.byteq.ai.ragstudio.infra.sdk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 模型供应商网关注册表
 * <p>
 * 按「专属 SDK 网关优先 → 通用 SDK 网关 → 手写协议兜底」的优先级为
 * 具体厂商 + 协议解析出正确的 {@link ProviderGateway}：
 * <pre>
 * ① 专属 SDK 网关（supports 命中厂商名 + 协议）：
 *      bailian+dashscope → DashScopeGateway
 *      zhipu+openai      → ZhipuGateway
 *      volcengine+openai → VolcEngineGateway
 * ② 通用 SDK 网关（按协议）：
 *      openai(默认)  → OpenAiGateway（openai-java，可配 baseUrl → 承载 DeepSeek/SiliconFlow 等）
 *      anthropic     → AnthropicGateway（anthropic-java，可配 baseUrl → 承载 DeepSeek 的 Anthropic 兼容接口）
 * ③ 最终兜底：
 *      OpenAiCompatibleGateway（现有手写协议层，兼容极端厂商）
 * </pre>
 * </p>
 * <p>
 * 网关通过 Spring 注入时按 {@link org.springframework.core.annotation.Order} 排序，
 * 优先匹配专属 SDK 网关，再按协议落到通用网关。
 * </p>
 */
@Slf4j
@Component
public class ProviderGatewayRegistry {

    /** 有序网关列表（OpenAiCompatibleGateway 作为兜底排最后，supports 恒为 false 不会主动命中） */
    private final List<ProviderGateway> gateways;

    /** 手写协议层兜底网关 */
    private final OpenAiCompatibleGateway fallback;

    public ProviderGatewayRegistry(List<ProviderGateway> gateways, OpenAiCompatibleGateway fallback) {
        this.gateways = gateways.stream()
                .filter(g -> g != fallback)
                .toList();
        this.fallback = fallback;
    }

    /**
     * 解析厂商 + 协议对应的网关。
     *
     * @param providerName 厂商名
     * @param protocolName 协议名（可空，空视为 openai）
     * @return 匹配到的网关，永不返回 null（最终回落到 {@link OpenAiCompatibleGateway}）
     */
    public ProviderGateway resolve(String providerName, String protocolName) {
        String protocol = normalizeProtocol(protocolName);
        for (ProviderGateway gateway : gateways) {
            if (gateway.supports(providerName, protocol)) {
                return gateway;
            }
        }
        log.debug("无专属/通用网关命中，回落到 OpenAiCompatibleGateway: provider={}, protocol={}", providerName, protocol);
        return fallback;
    }

    private String normalizeProtocol(String protocolName) {
        if (protocolName == null || protocolName.isBlank()) {
            return "openai";
        }
        return protocolName.trim().toLowerCase();
    }
}