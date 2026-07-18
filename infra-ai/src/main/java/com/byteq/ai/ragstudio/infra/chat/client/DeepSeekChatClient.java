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
import com.byteq.ai.ragstudio.infra.springai.AiLogHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DeepSeek ChatClient
 * <p>
 * 同步调用：直接通过 OkHttp 调用 API（支持 thinking.budget_tokens 等非标准参数）。
 * 流式调用：无思考时使用 LangChain4j；有非标准思考参数时使用 OkHttp + SSE 流式解析。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekChatClient implements ChatClient {

    private final LangChain4jModelFactory modelFactory;
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
        Map<String, Object> reqBody = modelFactory.buildRequestBody(request, target);
        String baseUrl = modelFactory.resolveBaseUrl(target);
        String apiKey = modelFactory.resolveApiKey(target);
        String traceId = String.valueOf(System.currentTimeMillis());

        try {
            String jsonBody = objectMapper.writeValueAsString(reqBody);
            AiLogHolder.log(traceId, "[REQ] " + jsonBody + "\n");

            Request httpReq = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            Response httpResp = okHttpClient.newCall(httpReq).execute();
            String respBody = httpResp.body() != null ? httpResp.body().string() : "";
            AiLogHolder.log(traceId, "[RESP] HTTP " + httpResp.code() + "\n" + respBody + "\n");

            if (!httpResp.isSuccessful()) {
                String snippet = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                throw new ModelClientException("DeepSeek HTTP " + httpResp.code() + " " + snippet,
                        ModelClientErrorType.fromHttpStatus(httpResp.code()), httpResp.code());
            }

            JsonNode root = objectMapper.readTree(respBody);
            JsonNode msg = root.path("choices").path(0).path("message");
            if (msg.has("content")) {
                return msg.get("content").asText();
            }
            throw new ModelClientException("DeepSeek 返回空响应",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        } catch (java.io.IOException e) {
            throw new ModelClientException("DeepSeek 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        // 判断是否需要通过原始 OkHttp 处理流式（当需要注入非标准 thinking 参数时）
        Map<String, Object> reasoningParams = modelFactory.resolveReasoningParams(request, target);
        boolean needsRawStream = !reasoningParams.isEmpty()
                && (reasoningParams.containsKey("thinking") || reasoningParams.containsKey("extra_body"));

        if (needsRawStream) {
            return rawStreamChat(request, callback, target, reasoningParams);
        }

        // 标准流式：使用 LangChain4j
        StreamingChatModel model = modelFactory.getOrCreateStreamingChatModel(target);
        dev.langchain4j.model.chat.request.ChatRequest lcRequest =
                modelFactory.buildLangChainChatRequest(request, target);
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        return LangChain4jStreamBridge.subscribe(model, lcRequest, callback, thinkingLevel, null);
    }

    /**
     * 通过原始 OkHttp + SSE 解析实现流式调用（支持 extra_body 等非标准参数注入）
     */
    private StreamCancellationHandle rawStreamChat(
            ChatRequest request, StreamCallback callback, ModelTarget target,
            Map<String, Object> reasoningParams) {

        String baseUrl = modelFactory.resolveBaseUrl(target);
        String apiKey = modelFactory.resolveApiKey(target);
        String traceId = String.valueOf(System.currentTimeMillis());
        AtomicBoolean terminated = new AtomicBoolean(false);

        Map<String, Object> reqBody = modelFactory.buildRequestBody(request, target, true);

        try {
            String jsonBody = objectMapper.writeValueAsString(reqBody);
            AiLogHolder.log(traceId, "[REQ-STREAM] " + jsonBody + "\n");

            Request httpReq = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            Thread sseThread = new Thread(() -> {
                try (Response httpResp = okHttpClient.newCall(httpReq).execute()) {
                    if (!httpResp.isSuccessful()) {
                        String errMsg = "DeepSeek 流式 HTTP " + httpResp.code();
                        if (!terminated.compareAndSet(false, true)) return;
                        callback.onError(new ModelClientException(errMsg,
                                ModelClientErrorType.fromHttpStatus(httpResp.code()), httpResp.code()));
                        return;
                    }

                    ResponseBody body = httpResp.body();
                    if (body == null) {
                        if (!terminated.compareAndSet(false, true)) return;
                        callback.onError(new ModelClientException("DeepSeek 流式响应体为空",
                                ModelClientErrorType.INVALID_RESPONSE, null));
                        return;
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null && !terminated.get()) {
                            if (line.isEmpty() || line.startsWith(":")) continue;

                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                try {
                                    JsonNode event = objectMapper.readTree(data);
                                    JsonNode choices = event.path("choices");
                                    if (choices.isArray() && choices.size() > 0) {
                                        JsonNode delta = choices.get(0).path("delta");
                                        if (delta.has("reasoning_content")) {
                                            String rc = delta.get("reasoning_content").asText();
                                            if (!rc.isEmpty()) {
                                                callback.onThinking(rc);
                                            }
                                        }
                                        if (delta.has("content")) {
                                            String content = delta.get("content").asText();
                                            if (!content.isEmpty()) {
                                                callback.onContent(content);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    log.warn("DeepSeek SSE 解析异常: {}", e.getMessage());
                                }
                            }
                        }
                    }

                    if (!terminated.compareAndSet(false, true)) return;
                    callback.onComplete();

                } catch (Exception e) {
                    if (!terminated.compareAndSet(false, true)) return;
                    callback.onError(e);
                }
            }, "deepseek-sse-" + traceId);
            sseThread.setDaemon(true);
            sseThread.start();

        } catch (Exception e) {
            if (terminated.compareAndSet(false, true)) {
                callback.onError(e);
            }
        }

        return () -> {
            if (terminated.compareAndSet(false, true)) {
                callback.onComplete();
            }
        };
    }
}
