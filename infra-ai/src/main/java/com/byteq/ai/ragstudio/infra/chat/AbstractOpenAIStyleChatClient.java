package com.byteq.ai.ragstudio.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagStreamTraceSupport;
import com.byteq.ai.ragstudio.framework.trace.RagStreamTraceSupport.StreamSpan;
import com.byteq.ai.ragstudio.infra.config.AIModelProperties;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.infra.http.HttpMediaTypes;
import com.byteq.ai.ragstudio.infra.http.HttpResponseHelper;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.http.ModelUrlResolver;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容协议 ChatClient 抽象基类
 * <p>
 * 使用模板方法模式封装了与 OpenAI 兼容 API 通信的公共逻辑：
 * - 统一的请求体构建（支持 stream、temperature、top_p、top_k、max_tokens 等参数）
 * - SSE 流式读取与解析（通过 OpenAIStyleSseParser + StreamAsyncExecutor）
 * - 同步/流式 HTTP 调用模板（doChat / doStreamChat）
 * - 链路追踪集成（RagStreamTraceSupport）
 * - 子类提供 provider 标识和钩子方法（customizeRequestBody、requiresApiKey 等）
 * <p>
 * 设计意图：
 * - 大幅减少具体 ChatClient 实现的重复代码：所有 OpenAI 兼容的提供商（百炼、SiliconFlow、Ollama 等）
 *   只需继承此类，实现 provider() 和少量钩子方法即可
 * - 同步和流式两种模式共享相同的请求构建逻辑，确保参数一致性
 * - Span 生命周期管理：在调用线程创建 StreamSpan，detach 后由异步线程的终态回调完成
 * <p>
 * 使用的 OkHttp 客户端分离：
 * - syncHttpClient：同步调用使用，配置较短超时（适用于即时响应）
 * - streamingHttpClient：流式调用使用，配置较长超时（适用于长连接）
 */
@Slf4j
public abstract class AbstractOpenAIStyleChatClient implements ChatClient {

    // 同步 HTTP 客户端（短超时）
    @Autowired
    private OkHttpClient syncHttpClient;

    // 流式 HTTP 客户端(长超时，适用于 SSE 长连接)
    @Autowired
    private OkHttpClient streamingHttpClient;

    // 流式任务异步执行的线程池，避免阻塞 Web 服务器线程
    @Autowired
    private Executor modelStreamExecutor;

    // 链路追踪支持，用于创建和管理 StreamSpan
    @Autowired
    private RagStreamTraceSupport streamTraceSupport;

    /** Gson 实例，用于 JSON 序列化/反序列化 */
    protected Gson gson = new Gson();

    // ==================== 子类钩子方法 ====================

    /**
     * 流式调用时是否启用 reasoning_content 解析
     * <p>
     * 默认根据请求中的 thinking 标志决定：开启思考链（thinking）时启用。
     * 子类可覆写此方法以适配不支持 reasoning_content 的模型。
     *
     * @param request 当前聊天请求
     * @return true 启用 reasoning_content 解析，false 跳过
     */
    protected boolean isReasoningEnabledForStream(ChatRequest request) {
        return Boolean.TRUE.equals(request.getThinking());
    }

    /**
     * 自定义请求体（钩子方法）
     * <p>
     * 子类可覆写此方法添加提供商特有的请求体字段。
     * 默认实现：当请求开启 thinking 时添加 enable_thinking 字段。
     * <p>
     * 调用时机：在 buildRequestBody 的末尾，所有公共参数设置完成后调用。
     *
     * @param body    正在构建的 JSON 请求体
     * @param request 当前聊天请求
     */
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        if (Boolean.TRUE.equals(request.getThinking())) {
            body.addProperty("enable_thinking", true);
        }
    }

    /**
     * 是否要求提供商配置 API Key
     * <p>
     * 覆盖父类控制是否需要 Bearer Token 认证。
     * 默认返回 true。本地推理服务（如 Ollama）应覆写为 false。
     *
     * @return true 需要 API Key，false 不需要
     */
    protected boolean requiresApiKey() {
        return true;
    }

    // ==================== 模板方法：同步调用 ====================

    /**
     * 同步聊天模板方法
     * <p>
     * 完整的同步调用流程：
     * 1. 解析 ProviderConfig 并校验 API Key（如果需要）
     * 2. 构建请求体（含 model、messages、temperature 等参数）
     * 3. 发送 HTTP POST 请求
     * 4. 检查响应状态码，失败时抛出 ModelClientException
     * 5. 解析响应 JSON 并提取 choices[0].message.content
     * <p>
     * 异常处理：
     * - HTTP 非 2xx：抛出 ModelClientException（含 HTTP 状态码）
     * - IO 异常（网络超时/断开）：抛出 ModelClientException（NETWORK_ERROR）
     * - 响应格式异常：抛出 ModelClientException（INVALID_RESPONSE）
     *
     * @param request ChatRequest 请求
     * @param target  目标模型配置
     * @return 模型返回的完整回答文本
     * @throws ModelClientException 请求失败或响应格式异常时抛出
     */
    protected String doChat(ChatRequest request, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        // 构建请求体（stream=false）
        JsonObject reqBody = buildRequestBody(request, target, false);
        Request requestHttp = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .build();

        JsonObject respJson;
        try (Response response = syncHttpClient.newCall(requestHttp).execute()) {
            // 检查 HTTP 状态码
            if (!response.isSuccessful()) {
                String body = safeReadBody(response);
                log.warn("{} 同步请求失败: status={}, body={}", provider(), response.code(), body);
                throw new ModelClientException(
                        provider() + " 同步请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            respJson = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            throw new ModelClientException(
                    provider() + " 同步请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        return extractChatContent(respJson);
    }

    // ==================== 模板方法：流式调用 ====================

    /**
     * 流式聊天模板方法
     * <p>
     * 完整的流式调用流程：
     * 1. 解析 ProviderConfig 并校验 API Key（如果需要）
     * 2. 构建请求体（stream=true）
     * 3. 创建 Http Call 并获取 StreamSpan（用于 trace 记录端到端耗时）
     * 4. 通过 StreamAsyncExecutor 异步执行 SSE 读取任务
     * 5. 返回组合的 StreamCancellationHandle（同时取消 HTTP 连接并收尾 span）
     * <p>
     * Span 生命周期管理：
     * - 在调用线程中创建 StreamSpan（beginStreamNode），确保子节点能正确继承父节点
     * - 创建后立即 detach，将 span 从当前线程栈弹出，避免污染兄弟节点的父子关系
     * - span 的 finish 由 StreamSpanCallback 在 SSE 终态（onComplete/onError）或 cancel 时触发
     * <p>
     * 异常处理：
     * - 流式读取过程中的异常由 doStream 内部处理，回调到 callback.onError()
     * - 取消操作通过返回的 cancellation handle 完成
     *
     * @param request  ChatRequest 请求
     * @param callback 流式回调接口
     * @param target   目标模型配置
     * @return 流式取消句柄，调用 cancel() 可中断流式响应
     */
    protected StreamCancellationHandle doStreamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        // 构建流式请求体（stream=true），添加 SSE Accept 头
        JsonObject reqBody = buildRequestBody(request, target, true);
        Request streamRequest = newAuthorizedRequest(provider, target)
                .post(RequestBody.create(reqBody.toString(), HttpMediaTypes.JSON))
                .addHeader("Accept", "text/event-stream")
                .build();

        Call call = streamingHttpClient.newCall(streamRequest);
        boolean reasoningEnabled = isReasoningEnabledForStream(request);

        // 在调用线程开 stream span，使后续 first-packet 子节点能正确归属父节点；
        // 该 span 由 SSE 终态（onComplete / onError）或 cancel 时收尾，记录真实端到端耗时
        StreamSpan span = streamTraceSupport.beginStreamNode(provider() + "-stream-chat", "LLM_PROVIDER");
        StreamSpanCallback wrappedCallback;
        try {
            wrappedCallback = new StreamSpanCallback(callback, span);
            // 异步提交流式读取任务到独立线程池
            StreamCancellationHandle inner = StreamAsyncExecutor.submit(
                    modelStreamExecutor,
                    call,
                    wrappedCallback,
                    cancelled -> doStream(call, wrappedCallback, cancelled, reasoningEnabled)
            );
            // 返回组合取消句柄：取消时先取消 HTTP 请求，再收尾 trace span
            return () -> {
                try {
                    inner.cancel();
                } finally {
                    wrappedCallback.onCancel();
                }
            };
        } finally {
            // 同步部分结束：把节点从当前线程的 NODE_STACK 弹出，避免污染兄弟节点的父节点链
            span.detach();
        }
    }

    /**
     * 流式 SSE 数据读取循环
     * <p>
     * 在异步线程中执行，通过 OkHttp 的阻塞式 execute 建立 SSE 连接，
     * 逐行读取响应流并通过 OpenAIStyleSseParser 解析。
     * <p>
     * 处理逻辑：
     * 1. 检查 HTTP 状态码
     * 2. 逐行读取 BufferedSource
     * 3. 每行调用 OpenAIStyleSseParser.parseLine 解析
     * 4. 根据解析结果回调 onThinking / onContent / onComplete
     * 5. 检测 cancelled 标志支持外部取消
     * 6. 流未正常结束且未被取消时抛出异常
     *
     * @param call             OkHttp Call
     * @param callback         流式回调（已包装为 StreamSpanCallback）
     * @param cancelled        取消标志
     * @param reasoningEnabled 是否启用 reasoning_content 解析
     */
    private void doStream(Call call, StreamCallback callback, AtomicBoolean cancelled, boolean reasoningEnabled) {
        try (Response response = call.execute()) {
            // 检查 HTTP 状态码
            if (!response.isSuccessful()) {
                String body = safeReadBody(response);
                throw new ModelClientException(
                        provider() + " 流式请求失败: HTTP " + response.code() + " - " + body,
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new ModelClientException(provider() + " 流式响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
            }
            BufferedSource source = body.source();
            boolean completed = false;
            // 逐行读取 SSE 数据流
            while (!cancelled.get()) {
                String line = source.readUtf8Line();
                if (line == null) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    // 解析一行 SSE 数据
                    OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(line, gson, reasoningEnabled);
                    // 先回调思考内容（如果模型输出 reasoning_content）
                    if (event.hasReasoning()) {
                        callback.onThinking(event.reasoning());
                    }
                    // 再回调增量内容
                    if (event.hasContent()) {
                        callback.onContent(event.content());
                    }
                    // 检测流式结束标记（finish_reason）
                    if (event.completed()) {
                        callback.onComplete();
                        completed = true;
                        break;
                    }
                } catch (Exception parseEx) {
                    log.warn("{} 流式响应解析失败: line={}", provider(), line, parseEx);
                }
            }
            // 取消路径：直接返回，不触发 onError
            if (cancelled.get()) {
                log.info("{} 流式响应已被取消", provider());
                return;
            }
            // 流未正常完成且未被取消 → 异常结束
            if (!completed) {
                throw new ModelClientException(provider() + " 流式响应异常结束", ModelClientErrorType.INVALID_RESPONSE, null);
            }
        } catch (Exception e) {
            // 非取消导致的异常才通知下游
            if (!cancelled.get()) {
                callback.onError(e);
            } else {
                log.info("{} 流式响应取消期间产生异常（可忽略）: {}", provider(), e.getMessage());
            }
        }
    }

    // ==================== 公共构建方法 ====================

    /**
     * 安全读取响应体，读取失败时返回占位符
     */
    private String safeReadBody(Response response) {
        try {
            return HttpResponseHelper.readBody(response.body());
        } catch (Exception e) {
            return "<无法读取响应体>";
        }
    }

    /**
     * 构建 OpenAI 兼容的 JSON 请求体
     * <p>
     * 公共参数包括：
     * - model：模型名称（必需）
     * - stream：是否流式（必需）
     * - messages：消息列表（必需）
     * - temperature / top_p / top_k：生成参数（可选）
     * - max_tokens：最大 Token 数（可选）
     * <p>
     * 最后调用 customizeRequestBody 钩子，允许子类添加提供商特有字段。
     *
     * @param request ChatRequest 请求
     * @param target  目标模型配置
     * @param stream  是否流式调用
     * @return 构建完成的 JSON 请求体
     */
    protected JsonObject buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        if (stream) {
            body.addProperty("stream", true);
        }

        body.add("messages", buildMessages(request));

        // 可选生成参数
        if (request.getTemperature() != null) {
            body.addProperty("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.addProperty("top_p", request.getTopP());
        }
        if (request.getTopK() != null) {
            body.addProperty("top_k", request.getTopK());
        }
        if (request.getMaxTokens() != null) {
            body.addProperty("max_tokens", request.getMaxTokens());
        }

        // 子类自定义字段
        customizeRequestBody(body, request);
        return body;
    }

    /**
     * 构建 messages 数组
     * <p>
     * 将 ChatMessage 列表转换为 OpenAI 兼容的 messages JSON 数组，
     * 每个元素包含 role（system/user/assistant）和 content 字段。
     */
    private JsonArray buildMessages(ChatRequest request) {
        JsonArray arr = new JsonArray();
        List<ChatMessage> messages = request.getMessages();
        if (CollUtil.isNotEmpty(messages)) {
            for (ChatMessage m : messages) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", toOpenAiRole(m.getRole()));
                msg.addProperty("content", m.getContent());
                arr.add(msg);
            }
        }
        return arr;
    }

    /**
     * 将统一角色枚举转换为 OpenAI 协议的角色字符串
     */
    private String toOpenAiRole(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
        };
    }

    /**
     * 构建带认证信息的 HTTP 请求构建器
     * <p>
     * 设置 URL（通过 ModelUrlResolver 解析）和 Authorization 头（如果需要 API Key）。
     */
    private Request.Builder newAuthorizedRequest(AIModelProperties.ProviderConfig provider, ModelTarget target) {
        Request.Builder builder = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.CHAT));
        if (requiresApiKey()) {
            builder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        return builder;
    }

    /**
     * 从同步响应 JSON 中提取聊天内容
     * <p>
     * 解析 OpenAI 格式的响应：choices[0].message.content
     * <p>
     * 校验路径：
     * - choices 数组存在且非空
     * - choices[0] 包含 message 对象
     * - message.content 存在且非 null
     *
     * @param respJson 响应 JSON 对象
     * @return 提取的文本内容
     * @throws ModelClientException 响应格式不符合预期时抛出
     */
    private String extractChatContent(JsonObject respJson) {
        if (respJson == null || !respJson.has("choices")) {
            throw new ModelClientException(provider() + " 响应缺少 choices", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonArray choices = respJson.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ModelClientException(provider() + " 响应 choices 为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject choice0 = choices.get(0).getAsJsonObject();
        if (choice0 == null || !choice0.has("message")) {
            throw new ModelClientException(provider() + " 响应缺少 message", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        JsonObject message = choice0.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new ModelClientException(provider() + " 响应缺少 content", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        return message.get("content").getAsString();
    }
}
