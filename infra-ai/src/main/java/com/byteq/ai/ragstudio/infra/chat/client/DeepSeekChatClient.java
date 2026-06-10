package com.byteq.ai.ragstudio.infra.chat.client;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.chat.AbstractOpenAIStyleChatClient;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DeepSeekChatClient extends AbstractOpenAIStyleChatClient {
    @Override
    public String provider() {
        return ModelProvider.DEEPSEEK.getId();
    }

    @Override
    @RagTraceNode(name = "deepseek-chat",type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request,target);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request,callback,target);
    }
}
