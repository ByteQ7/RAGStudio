package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.rerank.RerankClient;

import java.util.List;

/**
 * 网关重排序客户端薄适配器
 * <p>
 * 实现 {@link RerankClient}，在调用期按 {@link ModelTarget} 的协议
 * 通过 {@link ProviderGatewayRegistry} 解析出具体 {@link ProviderGateway} 后委托执行。
 * </p>
 */
public class GatewayRerankClient implements RerankClient {

    private final String providerName;
    private final ProviderGatewayRegistry registry;

    public GatewayRerankClient(String providerName, ProviderGatewayRegistry registry) {
        this.providerName = providerName;
        this.registry = registry;
    }

    @Override
    public String provider() {
        return providerName;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        return registry.resolve(providerName, target.protocolName()).rerank(query, candidates, topN, target);
    }
}