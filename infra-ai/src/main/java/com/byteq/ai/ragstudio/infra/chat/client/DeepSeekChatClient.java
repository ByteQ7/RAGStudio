package com.byteq.ai.ragstudio.infra.chat.client;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.chat.ChatClient;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.springai.FluxToStreamCallbackBridge;
import com.byteq.ai.ragstudio.infra.springai.SpringAiChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * DeepSeek ChatClient —— 基于 Spring AI OpenAI 兼容模式
 * <p>
 * 通过 DeepSeek 的 OpenAI 兼容 API 调用 DeepSeek-V3 / DeepSeek-R1 等模型。
 * 底层由 Spring AI 的 OpenAiChatModel 处理 HTTP 通信和 SSE 流式解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekChatClient implements ChatClient {

    private final SpringAiChatModelFactory modelFactory;

    @Override
    public String provider() {
        return ModelProvider.DEEPSEEK.getId();
    }

    @Override
    @RagTraceNode(name = "deepseek-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        ChatModel model = modelFactory.getOrCreateChatModel(target);
        Prompt prompt = modelFactory.toPrompt(request, target);
        ChatResponse response = model.call(prompt);
        return extractText(response, "deepseek");
    }

    @Override
    @RagTraceNode(name = "deepseek-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        ChatModel model = modelFactory.getOrCreateChatModel(target);
        Prompt prompt = modelFactory.toPrompt(request, target);
        Flux<ChatResponse> flux = model.stream(prompt);
        return FluxToStreamCallbackBridge.subscribe(flux, callback);
    }

    private static String extractText(ChatResponse response, String provider) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ModelClientException(
                    provider + " 返回空响应", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String text = response.getResult().getOutput().getText();
        return text != null ? text : "";
    }
}
