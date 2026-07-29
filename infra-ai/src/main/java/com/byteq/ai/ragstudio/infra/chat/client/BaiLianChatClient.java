package com.byteq.ai.ragstudio.infra.chat.client;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.chat.ChatClient;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelHttpClient;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class BaiLianChatClient implements ChatClient {

    private final HttpModelFactory modelFactory;
    private final ModelHttpClient httpClient;
    private final ProtocolRegistry protocolRegistry;

    public BaiLianChatClient(HttpModelFactory modelFactory, ModelHttpClient httpClient,
                              ProtocolRegistry protocolRegistry) {
        this.modelFactory = modelFactory;
        this.httpClient = httpClient;
        this.protocolRegistry = protocolRegistry;
    }

    @Override public String provider() { return ModelProvider.BAI_LIAN.getId(); }

    @Override
    @RagTraceNode(name = "bailian-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String url = modelFactory.resolveChatUrl(target);
        Map<String, Object> body = modelFactory.buildRequestBody(request, target);
        return httpClient.syncPost(url, target, body, protocol::extractChatContent);
    }

    @Override
    @RagTraceNode(name = "bailian-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String url = modelFactory.resolveChatUrl(target);
        Map<String, Object> body = modelFactory.buildRequestBody(request, target, true);
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        return httpClient.streamPost(url, target, body, callback, thinkingLevel, String.valueOf(System.currentTimeMillis()));
    }
}
