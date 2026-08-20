package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.infra.embedding.EmbeddingClient;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

import java.util.List;

/**
 * 网关嵌入客户端薄适配器
 * <p>
 * 实现 {@link EmbeddingClient}，在调用期按 {@link ModelTarget} 的协议
 * 通过 {@link ProviderGatewayRegistry} 解析出具体 {@link ProviderGateway} 后委托执行。
 * 每个模型提供商一个实例（provider 名区分）。
 * </p>
 */
public class GatewayEmbeddingClient implements EmbeddingClient {

    private final String providerName;
    private final ProviderGatewayRegistry registry;

    public GatewayEmbeddingClient(String providerName, ProviderGatewayRegistry registry) {
        this.providerName = providerName;
        this.registry = registry;
    }

    @Override
    public String provider() {
        return providerName;
    }

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        List<List<Float>> batch = embedBatch(List.of(text), target);
        return batch.isEmpty() ? List.of() : batch.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        return registry.resolve(providerName, target.protocolName()).embedBatch(texts, target);
    }

    @Override
    public List<List<Float>> embedImages(List<String> imageBase64List, ModelTarget target) {
        try {
            return registry.resolve(providerName, target.protocolName()).embedImages(imageBase64List, target);
        } catch (UnsupportedOperationException e) {
            throw new ModelClientException("当前 Embedding 客户端不支持图像嵌入: " + providerName, null, null, e);
        }
    }
}