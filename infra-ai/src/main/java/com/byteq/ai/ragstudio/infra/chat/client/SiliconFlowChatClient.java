package com.byteq.ai.ragstudio.infra.chat.client;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.chat.ChatClient;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.langchain4j.LangChain4jModelFactory;
import com.byteq.ai.ragstudio.infra.langchain4j.LangChain4jStreamBridge;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * SiliconFlow ChatClient —— 基于 LangChain4j 1.0.0
 * <p>
 * 通过 SiliconFlow 的 OpenAI 兼容 API 调用 DeepSeek / Qwen / GLM 等模型。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiliconFlowChatClient implements ChatClient {

    private final LangChain4jModelFactory modelFactory;

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    @RagTraceNode(name = "siliconflow-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        ChatModel model = modelFactory.getOrCreateChatModel(target);
        dev.langchain4j.model.chat.request.ChatRequest lcRequest =
                modelFactory.buildLangChainChatRequest(request, target);
        ChatResponse response = model.chat(lcRequest);
        return extractText(response, "siliconflow");
    }

    @Override
    @RagTraceNode(name = "siliconflow-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        StreamingChatModel model = modelFactory.getOrCreateStreamingChatModel(target);
        dev.langchain4j.model.chat.request.ChatRequest lcRequest =
                modelFactory.buildLangChainChatRequest(request, target);
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        return LangChain4jStreamBridge.subscribe(model, lcRequest, callback, thinkingLevel, null);
    }

    private static String extractText(ChatResponse response, String provider) {
        if (response == null || response.aiMessage() == null) {
            throw new ModelClientException(
                    provider + " 返回空响应", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String text = response.aiMessage().text();
        return text != null ? text : "";
    }
}
