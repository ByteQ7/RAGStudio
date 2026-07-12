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
import com.byteq.ai.ragstudio.infra.springai.AiLogHolder;
import com.byteq.ai.ragstudio.infra.springai.FluxToStreamCallbackBridge;
import com.byteq.ai.ragstudio.infra.springai.SpringAiChatModelFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek ChatClient —— 绕过 Spring AI，直接通过 OkHttp 调用 API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekChatClient implements ChatClient {

    private final SpringAiChatModelFactory modelFactory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(120))
            .build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Override
    public String provider() {
        return ModelProvider.DEEPSEEK.getId();
    }

    @Override
    @RagTraceNode(name = "deepseek-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        Map<String, Object> reasoningParams = modelFactory.getReasoningRouter().route(
                target.candidate().getModel(), thinkingLevel);

        // 构建 messages
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        if (request.getMessages() != null) {
            for (com.byteq.ai.ragstudio.framework.convention.ChatMessage msg : request.getMessages()) {
                Map<String, Object> m = new LinkedHashMap<>();
                String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user";
                m.put("role", role);
                m.put("content", msg.getContent() != null ? msg.getContent() : "");
                messages.add(m);
            }
        }

        // 构建请求体
        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("model", target.candidate().getModel());
        reqBody.put("messages", messages);
        reqBody.put("stream", false);
        if (request.getTemperature() != null) reqBody.put("temperature", request.getTemperature());
        if (request.getTopP() != null) reqBody.put("top_p", request.getTopP());
        if (request.getMaxTokens() != null) reqBody.put("max_tokens", request.getMaxTokens());
        if (!reasoningParams.isEmpty()) reqBody.putAll(reasoningParams);

        String baseUrl = modelFactory.resolveBaseUrl(target);
        String apiKey = modelFactory.resolveApiKey(target);
        String traceId = String.valueOf(System.currentTimeMillis());

        try {
            String jsonBody = objectMapper.writeValueAsString(reqBody);
            AiLogHolder.log(traceId, "[REQ] Model: " + target.candidate().getModel()
                    + " | ThinkingLevel: " + thinkingLevel
                    + " | Body: " + jsonBody + "\n");

            Request httpReq = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            Response httpResp = okHttpClient.newCall(httpReq).execute();
            String respBody = httpResp.body() != null ? httpResp.body().string() : "";

            AiLogHolder.log(traceId, "=== AI Provider Raw Response ===\n"
                    + "Model: " + target.candidate().getModel() + "\n"
                    + "HTTP: " + httpResp.code() + "\n"
                    + respBody + "\n=== End ===\n");

            if (!httpResp.isSuccessful()) {
                String snippet = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                throw new ModelClientException("DeepSeek API error: HTTP " + httpResp.code() + " " + snippet,
                        ModelClientErrorType.fromHttpStatus(httpResp.code()), httpResp.code());
            }

            JsonNode root = objectMapper.readTree(respBody);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode msg = choices.get(0).get("message");
                if (msg != null && msg.has("content")) {
                    return msg.get("content").asText();
                }
            }
            throw new ModelClientException("DeepSeek 返回空响应", ModelClientErrorType.INVALID_RESPONSE, null);
        } catch (java.io.IOException e) {
            throw new ModelClientException("DeepSeek API 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        ChatModel model = modelFactory.getOrCreateChatModel(target);
        Prompt prompt = modelFactory.toPrompt(request, target);
        Flux<ChatResponse> flux = model.stream(prompt);
        return FluxToStreamCallbackBridge.subscribe(flux, callback,
                request.getThinkingLevel() != null ? request.getThinkingLevel() : 0, null);
    }
}
