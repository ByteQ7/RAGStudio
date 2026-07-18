package com.byteq.ai.ragstudio.infra.langchain4j;

import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 LangChain4j 的流式响应桥接到项目的 StreamCallback 回调体系
 * <p>
 * 使用 LangChain4j 1.0.0 的 {@link StreamingChatResponseHandler}，
 * 替代原有的 {@code FluxToStreamCallbackBridge}。
 * </p>
 */
@Slf4j
public class LangChain4jStreamBridge {

    /**
     * 订阅 LangChain4j 流式模型并桥接到 StreamCallback
     *
     * @param model         LangChain4j 流式模型
     * @param chatRequest   LangChain4j 请求对象（含消息和参数）
     * @param callback      项目的流式回调
     * @param thinkingLevel 思考深度（>0 时在 onCompleteResponse 提取 reasoning content）
     * @param taskId        任务 ID（日志用）
     * @return 取消句柄
     */
    public static StreamCancellationHandle subscribe(
            StreamingChatModel model,
            ChatRequest chatRequest,
            StreamCallback callback,
            int thinkingLevel,
            String taskId) {

        final String finalTaskId = (taskId != null && !taskId.isEmpty()) ? taskId : String.valueOf(System.currentTimeMillis());
        AtomicBoolean terminated = new AtomicBoolean(false);

        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                if (terminated.get()) return;
                try {
                    if (partialResponse != null && !partialResponse.isEmpty()) {
                        callback.onContent(partialResponse);
                    }
                } catch (Exception e) {
                    log.warn("流式内容回调异常: {}", e.getMessage());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                if (!terminated.compareAndSet(false, true)) return;
                try {
                    if (completeResponse != null && completeResponse.aiMessage() != null
                            && thinkingLevel > 0) {
                        // AiMessage 在 1.0.0 中没有 reasoningContent 方法，
                        // 思考内容仅在 DeepSeek 的原始 OkHttp SSE 路径中处理
                        String text = completeResponse.aiMessage().text();
                        if (log.isDebugEnabled()) {
                            log.debug("流式完成: {} chars", text != null ? text.length() : 0);
                        }
                    }
                } catch (Exception e) {
                    log.warn("流式完成处理异常: {}", e.getMessage());
                }
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                if (terminated.compareAndSet(false, true)) {
                    callback.onError(error);
                }
            }
        };

        try {
            model.chat(chatRequest, handler);
        } catch (Exception e) {
            log.error("流式模型调用启动失败", e);
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
