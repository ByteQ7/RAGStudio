package com.byteq.ai.ragstudio.infra.agentscope;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.chat.ChatClient;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于 AgentScope 的聊天客户端
 * <p>
 * 实现 {@link ChatClient} 接口，内部委托 AgentScope {@link Model} 完成
 * 同步 / 流式对话。每个调用按 {@link ModelTarget} 动态构建模型实例（AgentScope
 * 模型非线程安全，按请求构建实例与项目现有模式一致）。
 * </p>
 */
@Slf4j
public class AgentScopeChatClient implements ChatClient {

    /** 流式首包启动等待超时（与旧 OkHttp callTimeout 45s 对齐，超时视为启动失败以便路由层 fallback） */
    private static final long STARTUP_TIMEOUT_MS = 45_000;

    private final String providerId;
    private final AgentScopeModelFactory modelFactory;

    public AgentScopeChatClient(String providerId, AgentScopeModelFactory modelFactory) {
        this.providerId = providerId;
        this.modelFactory = modelFactory;
    }

    @Override
    public String provider() {
        return providerId;
    }

    @Override
    @RagTraceNode(name = "agentscope-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        Model model = modelFactory.buildChatModel(target);
        List<Msg> msgs = modelFactory.convertMessages(request);
        GenerateOptions options = modelFactory.buildOptions(request, target, false);

        StringBuilder content = new StringBuilder();
        ChatResponse finalResponse;
        try {
            finalResponse = model.stream(msgs, null, options)
                    .doOnError(e -> log.debug("AgentScope 同步调用异常 model={}, err={}",
                            target.candidate().getModel(), e.getMessage()))
                    .blockLast(Duration.ofMillis(STARTUP_TIMEOUT_MS));
        } catch (IllegalStateException e) {
            // blockLast(timeout) 超时抛 IllegalStateException
            throw new RemoteException("同步调用超时（" + STARTUP_TIMEOUT_MS / 1000 + "s）",
                    e, BaseErrorCode.REMOTE_ERROR);
        }
        if (finalResponse != null && finalResponse.getContent() != null) {
            finalResponse.getContent().forEach(block -> appendText(block, content));
        }
        return content.toString();
    }

    @Override
    @RagTraceNode(name = "agentscope-stream-chat", type = "LLM_PROVIDER")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        Model model = modelFactory.buildChatModel(target);
        List<Msg> msgs = modelFactory.convertMessages(request);
        GenerateOptions options = modelFactory.buildOptions(request, target, true);

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean firstReceived = new AtomicBoolean(false);
        AtomicReference<Throwable> startupError = new AtomicReference<>();
        CountDownLatch firstChunk = new CountDownLatch(1);

        Disposable[] holder = new Disposable[1];
        holder[0] = model.stream(msgs, null, options)
                .subscribe(
                        response -> {
                            if (firstReceived.compareAndSet(false, true)) {
                                firstChunk.countDown();
                            }
                            forwardResponse(response, callback);
                        },
                        error -> {
                            if (firstReceived.compareAndSet(false, true)) {
                                // 首包前失败：记录启动错误，由路由层 fallback 到下一个模型
                                startupError.set(error);
                                firstChunk.countDown();
                            } else if (finished.compareAndSet(false, true)) {
                                callback.onError(error);
                            }
                        },
                        () -> {
                            // 首包前即完成（空流）：视为正常启动，后续 onComplete 正常触发
                            if (firstReceived.compareAndSet(false, true)) {
                                firstChunk.countDown();
                            }
                            if (finished.compareAndSet(false, true)) {
                                callback.onComplete();
                            }
                        });

        // 同步等待首包：与旧 OkHttp 同步发起请求的语义一致，
        // 启动失败（网络不通/鉴权失败/超时）在此同步抛出，路由层才能切换 fallback 模型
        try {
            if (!firstChunk.await(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                holder[0].dispose();
                throw new RemoteException("流式请求启动超时（" + STARTUP_TIMEOUT_MS / 1000 + "s）",
                        BaseErrorCode.REMOTE_ERROR);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            holder[0].dispose();
            throw new RemoteException("流式请求启动被中断", e, BaseErrorCode.REMOTE_ERROR);
        }
        Throwable startupFailure = startupError.get();
        if (startupFailure != null) {
            holder[0].dispose();
            throw new RemoteException("流式请求启动失败", startupFailure, BaseErrorCode.REMOTE_ERROR);
        }

        return () -> {
            if (finished.compareAndSet(false, true)) {
                // 取消语义：不再触发 onComplete，由上层 HealthTrackingCallback 标记取消
                holder[0].dispose();
            }
        };
    }

    private void forwardResponse(ChatResponse response, StreamCallback callback) {
        if (response.getContent() == null) {
            return;
        }
        for (ContentBlock block : response.getContent()) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                if (text != null && !text.isEmpty()) {
                    callback.onContent(text);
                }
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                String thinking = thinkingBlock.getThinking();
                if (thinking != null && !thinking.isEmpty()) {
                    callback.onThinking(thinking);
                }
            }
        }
    }

    private void appendText(ContentBlock block, StringBuilder content) {
        if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
            content.append(textBlock.getText());
        }
    }
}
