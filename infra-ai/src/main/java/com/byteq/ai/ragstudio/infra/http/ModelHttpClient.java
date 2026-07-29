package com.byteq.ai.ragstudio.infra.http;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.infra.config.ModelRoutingProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一 LLM HTTP 客户端
 * <p>
 * 提供同步聊天、流式聊天、Embedding、连通性探测等所有 HTTP 调用的公共实现。
 * 负责连接管理、超时控制、错误码映射、SSE 解析、响应提取，
 * 不依赖任何具体模型或提供商。
 * </p>
 */
@Slf4j
@Component
public class ModelHttpClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient okHttpClient;

    private final ProtocolRegistry protocolRegistry;

    public ModelHttpClient(ProtocolRegistry protocolRegistry, ModelRoutingProperties routingProperties) {
        this.protocolRegistry = protocolRegistry;
        ModelRoutingProperties.Http http = routingProperties.getHttp();
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(http.getConnectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(http.getReadTimeoutSeconds()))
                .build();
    }

    // ==================== 同步 POST ====================

    /**
     * 同步 POST 请求（协议感知），返回 JSON 响应树
     */
    public <T> T syncPost(String url, ModelTarget target, Object body, ResponseParser<T> parser) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        try {
            String jsonBody = MAPPER.writeValueAsString(body);
            Request httpReq = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header(protocol.authHeaderName(), protocol.authHeaderValue(target.provider().getApiKey()))
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            try (Response httpResp = okHttpClient.newCall(httpReq).execute()) {
                String respBody = httpResp.body() != null ? httpResp.body().string() : "";
                if (!httpResp.isSuccessful()) {
                    String snippet = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                    throw new ModelClientException("HTTP " + httpResp.code() + " " + snippet,
                            ModelClientErrorType.fromHttpStatus(httpResp.code()), httpResp.code());
                }
                JsonNode root = MAPPER.readTree(respBody);
                return parser.parse(root);
            }
        } catch (java.io.IOException e) {
            throw new ModelClientException("请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    /**
     * 同步 POST 请求（兼容旧代码，默认 Bearer 认证），返回 JSON 响应树
     */
    public <T> T syncPost(String url, String apiKey, Object body, ResponseParser<T> parser) {
        try {
            String jsonBody = MAPPER.writeValueAsString(body);
            Request httpReq = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();

            try (Response httpResp = okHttpClient.newCall(httpReq).execute()) {
                String respBody = httpResp.body() != null ? httpResp.body().string() : "";
                if (!httpResp.isSuccessful()) {
                    String snippet = respBody.length() > 200 ? respBody.substring(0, 200) : respBody;
                    throw new ModelClientException("HTTP " + httpResp.code() + " " + snippet,
                            ModelClientErrorType.fromHttpStatus(httpResp.code()), httpResp.code());
                }
                JsonNode root = MAPPER.readTree(respBody);
                return parser.parse(root);
            }
        } catch (java.io.IOException e) {
            throw new ModelClientException("请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }
    }

    /**
     * 同步 POST，不解析响应体（连通性测试用）
     */
    public boolean syncPostForStatus(String url, String apiKey, Object body) {
        try {
            String jsonBody = MAPPER.writeValueAsString(body);
            Request req = new Request.Builder()
                    .url(url).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(jsonBody, JSON)).build();
            try (Response resp = okHttpClient.newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 流式 POST ====================

    /**
     * 流式 SSE POST
     */
    public StreamCancellationHandle streamPost(
            String url, String apiKey, Map<String, Object> body,
            StreamCallback callback, int thinkingLevel, String traceId) {

        String finalTraceId = (traceId != null && !traceId.isEmpty()) ? traceId : String.valueOf(System.currentTimeMillis());
        AtomicBoolean terminated = new AtomicBoolean(false);

        try {
            String jsonBody = MAPPER.writeValueAsString(body);
            Request httpReq = new Request.Builder()
                    .url(url).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(jsonBody, JSON)).build();

            Thread sseThread = new Thread(() -> {
                try (Response httpResp = okHttpClient.newCall(httpReq).execute()) {
                    if (!httpResp.isSuccessful()) {
                        if (!terminated.compareAndSet(false, true)) return;
                        callback.onError(new ModelClientException("流式 HTTP " + httpResp.code(),
                                ModelClientErrorType.fromHttpStatus(httpResp.code()), httpResp.code()));
                        return;
                    }
                    ResponseBody respBody = httpResp.body();
                    if (respBody == null) {
                        if (!terminated.compareAndSet(false, true)) return;
                        callback.onError(new ModelClientException("流式响应体为空",
                                ModelClientErrorType.INVALID_RESPONSE, null));
                        return;
                    }
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(respBody.byteStream()))) {
                        String line;
                        while ((line = r.readLine()) != null && !terminated.get()) {
                            if (line.isEmpty() || line.startsWith(":")) continue;
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) break;
                                try {
                                    JsonNode event = MAPPER.readTree(data);
                                    JsonNode choices = event.path("choices");
                                    if (choices.isArray() && choices.size() > 0) {
                                        JsonNode delta = choices.get(0).path("delta");
                                        if (delta.has("reasoning_content")) {
                                            String rc = delta.get("reasoning_content").asText();
                                            if (!rc.isEmpty()) callback.onThinking(rc);
                                        }
                                        if (delta.has("content")) {
                                            String c = delta.get("content").asText();
                                            if (!c.isEmpty()) callback.onContent(c);
                                        }
                                    }
                                } catch (Exception e) { log.warn("SSE 解析异常: {}", e.getMessage()); }
                            }
                        }
                    }
                    if (!terminated.compareAndSet(false, true)) return;
                    callback.onComplete();
                } catch (Exception e) {
                    if (!terminated.compareAndSet(false, true)) return;
                    callback.onError(e);
                }
            }, "http-sse-" + finalTraceId);
            sseThread.setDaemon(true);
            sseThread.start();
        } catch (Exception e) {
            if (terminated.compareAndSet(false, true)) callback.onError(e);
        }
        return () -> { if (terminated.compareAndSet(false, true)) callback.onComplete(); };
    }

    /**
     * 流式 SSE POST（协议感知，使用 ModelTarget 中的认证方式 + SSE 解析）
     */
    public StreamCancellationHandle streamPost(
            String url, ModelTarget target, Map<String, Object> body,
            StreamCallback callback, int thinkingLevel, String traceId) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        String finalTraceId = (traceId != null && !traceId.isEmpty()) ? traceId : String.valueOf(System.currentTimeMillis());
        AtomicBoolean terminated = new AtomicBoolean(false);

        try {
            String jsonBody = MAPPER.writeValueAsString(body);
            Request httpReq = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header(protocol.authHeaderName(), protocol.authHeaderValue(target.provider().getApiKey()))
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(jsonBody, JSON)).build();

            Thread sseThread = new Thread(() -> {
                try (Response httpResp = okHttpClient.newCall(httpReq).execute()) {
                    if (!httpResp.isSuccessful()) {
                        if (!terminated.compareAndSet(false, true)) return;
                        callback.onError(new ModelClientException("流式 HTTP " + httpResp.code(),
                                ModelClientErrorType.fromHttpStatus(httpResp.code()), httpResp.code()));
                        return;
                    }
                    ResponseBody respBody = httpResp.body();
                    if (respBody == null) {
                        if (!terminated.compareAndSet(false, true)) return;
                        callback.onError(new ModelClientException("流式响应体为空",
                                ModelClientErrorType.INVALID_RESPONSE, null));
                        return;
                    }
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(respBody.byteStream()))) {
                        String line;
                        while ((line = r.readLine()) != null && !terminated.get()) {
                            if (line.isEmpty() || line.startsWith(":")) continue;
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if ("[DONE]".equals(data)) break;
                                try {
                                    JsonNode event = MAPPER.readTree(data);
                                    protocol.parseStreamChunk(event, callback);
                                } catch (Exception e) { log.warn("SSE 解析异常: {}", e.getMessage()); }
                            }
                        }
                    }
                    if (!terminated.compareAndSet(false, true)) return;
                    callback.onComplete();
                } catch (Exception e) {
                    if (!terminated.compareAndSet(false, true)) return;
                    callback.onError(e);
                }
            }, "http-sse-" + finalTraceId);
            sseThread.setDaemon(true);
            sseThread.start();
        } catch (Exception e) {
            if (terminated.compareAndSet(false, true)) callback.onError(e);
        }
        return () -> { if (terminated.compareAndSet(false, true)) callback.onComplete(); };
    }

    // ==================== 响应解析 ====================

    /**
     * 从 OpenAI 兼容响应中提取聊天文本
     */
    public String extractChatContent(JsonNode root, String provider) {
        JsonNode msg = root.path("choices").path(0).path("message");
        if (msg.has("content")) return msg.get("content").asText();
        throw new ModelClientException(provider + " 返回空响应", ModelClientErrorType.INVALID_RESPONSE, null);
    }

    /**
     * 从 OpenAI 兼容响应中提取向量列表
     */
    public List<List<Float>> extractEmbeddings(JsonNode root) {
        JsonNode data = root.path("data");
        List<List<Float>> results = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                JsonNode emb = item.path("embedding");
                List<Float> vec = new ArrayList<>();
                if (emb.isArray()) for (JsonNode v : emb) vec.add((float) v.asDouble());
                results.add(vec);
            }
        }
        return results;
    }

    @FunctionalInterface
    public interface ResponseParser<T> {
        T parse(JsonNode root);
    }

    // ==================== Agent 工具调用 ====================

    /**
     * 工具调用结果
     */
    public record ToolCallInfo(String id, String name, Map<String, Object> arguments) {}

    /**
     * Agent 响应结果
     */
    public record AgentResponse(
            List<ToolCallInfo> toolCalls,
            String content,
            String finishReason
    ) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    /**
     * 同步 POST，返回包含 tool_calls 的 Agent 响应
     */
    public AgentResponse syncPostForAgent(String url, String apiKey, Object body) {
        return syncPost(url, apiKey, body, root -> {
            List<ToolCallInfo> toolCalls = new ArrayList<>();
            String content = "";
            String finishReason = root.path("choices").path(0).path("finish_reason").asText();

            JsonNode msg = root.path("choices").path(0).path("message");
            if (msg.has("content") && !msg.get("content").isNull()) {
                content = msg.get("content").asText();
            }
            JsonNode tcs = msg.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    String id = tc.path("id").asText();
                    String name = tc.path("function").path("name").asText();
                    Map<String, Object> args = new java.util.LinkedHashMap<>();
                    try {
                        String argsStr = tc.path("function").path("arguments").asText();
                        JsonNode argsNode = MAPPER.readTree(argsStr);
                        if (argsNode.isObject()) {
                            argsNode.fields().forEachRemaining(e ->
                                args.put(e.getKey(), e.getValue().asText()));
                        }
                    } catch (Exception ignored) {}
                    toolCalls.add(new ToolCallInfo(id, name, args));
                }
            }
            return new AgentResponse(toolCalls, content, finishReason);
        });
    }
}
