package com.byteq.ai.ragstudio.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.model.ModelHealthStore;
import com.byteq.ai.ragstudio.infra.model.ModelRoutingExecutor;
import com.byteq.ai.ragstudio.infra.model.ModelSelector;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 * <p>
 * 实现 {@link LLMService} 接口，提供基于健康检查的智能路由能力。
 * 同步调用通过 {@link ModelRoutingExecutor} 执行带 fallback 的模型调用，
 * 流式调用按优先级逐模型尝试，失败时自动切换到下一个候选模型。
 * <p>
 * 设计意图：
 * - 同步路由：通过 ModelSelector 获取候选模型列表，按优先级逐次尝试，
 *   失败时自动 fallback 到下一个候选模型
 * - 流式路由：按优先级遍历候选模型，跳过不健康或不可用的模型，
 *   成功启动后直接返回取消句柄，失败时自动切换
 * - 健康状态管理：每次调用成功后标记 markSuccess，失败后标记 markFailure，
 *   供 ModelSelector 在后续路由决策中参考
 * <p>
 * 使用场景：
 * - 业务层通过 LLMService 接口注入，无需关心底层模型路由逻辑
 * - 支持多模型提供商混合部署（如阿里云百炼 + SiliconFlow + DeepSeek）
 * - 深度思考（deep thinking）场景下筛选支持 thinking 的模型
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private static final String NO_MODEL_OR_APIKEY_MESSAGE = "未设置模型或API KEY，请检查";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;
    private final ModelHealthStore healthStore;
    private final ModelRoutingExecutor executor;
    private final Map<String, ChatClient> clientsByProvider;

    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(
                        ChatClient::provider,
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("重复的 ChatClient provider '{}', 使用 {}", existing.provider(), replacement.getClass().getSimpleName());
                            return replacement;
                        }));
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        List<ModelTarget> targets = selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()));
        validateTargets(targets);
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                targets,
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    @Override
    public String chat(ChatRequest request, String modelId) {
        if (!StringUtils.hasText(modelId)) {
            return chat(request);
        }
        List<ModelTarget> targets = List.of(resolveTarget(modelId, Boolean.TRUE.equals(request.getThinking())));
        validateTargets(targets);
        return executor.executeWithFallback(
                ModelCapability.CHAT,
                targets,
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );
    }

    /**
     * 流式聊天（带路由 + 自动 fallback）
     * <p>
     * 核心流程：
     * 1. 通过 ModelSelector 获取候选模型列表（支持 deep thinking 筛选）
     * 2. 遍历候选模型，跳过健康检查未通过或客户端缺失的模型
     * 3. 调用 client.streamChat 发起流式请求
     * 4. 成功 → 标记健康、返回取消句柄
     * 5. 失败 → 标记不健康、尝试下一个模型
     * 6. 所有模型均失败 → 回调 onError 并抛出 RemoteException
     *
     * @param request  ChatRequest 请求
     * @param callback 流式回调接口
     * @return 流式取消句柄
     * @throws RemoteException 所有候选模型均启动失败时抛出
     */
    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        List<ModelTarget> targets = selector.selectChatCandidates(Boolean.TRUE.equals(request.getThinking()));
        validateTargets(targets);

        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        for (ModelTarget target : targets) {
            if (!ModelProvider.NOOP.matches(target.candidate().getProvider())
                    && (target.provider() == null || !StringUtils.hasText(target.provider().getApiKey()))) {
                log.warn("{} 提供商未配置 API Key, 跳过: provider={}, modelId={}",
                        label, target.candidate().getProvider(), target.id());
                continue;
            }
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            StreamCancellationHandle handle;
            try {
                handle = client.streamChat(request, callback, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException("流式请求启动失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            healthStore.markSuccess(target.id());
            return handle;
        }

        throw notifyAllFailed(callback, lastError);
    }

    private void validateTargets(List<ModelTarget> targets) {
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(NO_MODEL_OR_APIKEY_MESSAGE);
        }
        boolean hasValidTarget = targets.stream()
                .anyMatch(t -> t.provider() != null
                        && (ModelProvider.NOOP.matches(t.candidate().getProvider())
                            || StringUtils.hasText(t.provider().getApiKey())));
        if (!hasValidTarget) {
            throw new RemoteException(NO_MODEL_OR_APIKEY_MESSAGE);
        }
    }

    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    private ModelTarget resolveTarget(String modelId, boolean deepThinking) {
        return selector.selectChatCandidates(deepThinking).stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Chat 模型不可用: " + modelId));
    }
}
