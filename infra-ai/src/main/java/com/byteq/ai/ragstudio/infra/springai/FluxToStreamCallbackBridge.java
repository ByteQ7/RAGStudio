package com.byteq.ai.ragstudio.infra.springai;

import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 Spring AI 的 Flux&lt;ChatResponse&gt; 流式输出桥接到项目的 StreamCallback 回调体系
 * <p>
 * 职责：
 * - 订阅 Flux 并将 onContent / onThinking / onComplete / onError 事件转发到 StreamCallback
 * - 提取 thinking/reasoning 内容（DeepSeek-R1 等思考模型的 reasoning_content）
 * - 忽略空内容 chunk（Spring AI 流式输出可能包含空的增量片段）
 * - 提供基于 Reactor Disposable 的取消能力，包装为 StreamCancellationHandle
 * - 保证 onComplete / onError 只触发一次（通过 AtomicBoolean 防止重复回调）
 * <p>
 * 线程安全说明：
 * Flux 的回调可能运行在 Reactor Netty 的事件循环线程上，
 * StreamCallback 的各实现（ForwardingStreamCallback 等）已设计为线程安全。
 */
@Slf4j
public class FluxToStreamCallbackBridge {

    /**
     * 订阅 Flux 并桥接到 StreamCallback
     *
     * @param flux     Spring AI 的流式 ChatResponse 序列
     * @param callback 项目的流式回调接口
     * @return 取消句柄，调用 cancel() 可中断流式响应
     */
    public static StreamCancellationHandle subscribe(reactor.core.publisher.Flux<ChatResponse> flux,
                                                      StreamCallback callback) {
        AtomicBoolean terminated = new AtomicBoolean(false);

        Disposable disposable = flux.subscribe(
                chunk -> {
                    if (terminated.get()) return;
                    try {
                        // 提取思考/推理内容（DeepSeek-R1、Qwen3 等思考模型）
                        String thinking = extractThinkingContent(chunk);
                        if (thinking != null && !thinking.isEmpty()) {
                            callback.onThinking(thinking);
                        }
                        // 提取正文内容
                        String text = extractContent(chunk);
                        if (text != null && !text.isEmpty()) {
                            callback.onContent(text);
                        }
                    } catch (Exception e) {
                        log.warn("流式响应内容提取失败: {}", e.getMessage());
                    }
                },
                error -> {
                    if (terminated.compareAndSet(false, true)) {
                        callback.onError(error);
                    }
                },
                () -> {
                    if (terminated.compareAndSet(false, true)) {
                        callback.onComplete();
                    }
                }
        );

        return () -> {
            if (terminated.compareAndSet(false, true)) {
                disposable.dispose();
                // 取消时通知 callback 流式结束，确保资源（如 trace span）被释放
                callback.onComplete();
            }
        };
    }

    /**
     * 从 ChatResponse chunk 中提取正文文本
     * <p>
     * Spring AI 流式输出的每个 chunk 包含一个 Generation，
     * 其中 AssistantMessage 的 getText() 返回增量文本片段。
     */
    private static String extractContent(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null) return null;
        var output = chunk.getResult().getOutput();
        if (output == null) return null;
        return output.getText();
    }

    /**
     * 从 ChatResponse chunk 中提取思考/推理内容
     * <p>
     * 思考模型（如 DeepSeek-R1、Qwen3 等）在流式输出时会通过 metadata
     * 传递 reasoning_content 字段。Spring AI 的 OpenAI 兼容适配器将此字段
     * 存储在 AssistantMessage 的 metadata map 中，key 为 "reasoningContent"。
     *
     * @param chunk Spring AI ChatResponse
     * @return 推理内容片段，如果不支持或为空则返回 null
     */
    private static String extractThinkingContent(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null) return null;
        var output = chunk.getResult().getOutput();
        if (output == null) return null;
        try {
            var metadata = output.getMetadata();
            if (metadata == null) return null;
            Object reasoning = metadata.get("reasoningContent");
            return reasoning instanceof String s ? s : null;
        } catch (Exception e) {
            // metadata 获取失败不影响主流程
            return null;
        }
    }
}
