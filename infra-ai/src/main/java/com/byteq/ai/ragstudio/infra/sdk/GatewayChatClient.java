package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.ChatClient;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

/**
 * 网关聊天客户端薄适配器
 * <p>
 * 实现 {@link ChatClient}，在调用期按 {@link ModelTarget} 的协议
 * 通过 {@link ProviderGatewayRegistry} 解析出具体 {@link ProviderGateway} 后委托执行。
 * 每个模型提供商一个实例（provider 名区分）。
 * </p>
 */
public class GatewayChatClient implements ChatClient {

    private final String providerName;
    private final ProviderGatewayRegistry registry;

    public GatewayChatClient(String providerName, ProviderGatewayRegistry registry) {
        this.providerName = providerName;
        this.registry = registry;
    }

    @Override
    public String provider() {
        return providerName;
    }

    @Override
    public String chat(ChatRequest request, ModelTarget target) {
        return registry.resolve(providerName, target.protocolName()).chat(request, target);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return registry.resolve(providerName, target.protocolName()).streamChat(request, callback, target);
    }
}