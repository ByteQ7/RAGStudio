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
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.infra.springai.AiLogHolder;
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

    /**
     * 同步聊天（带路由 + 自动 fallback）
     * <p>
     * 通过 ModelSelector 选取候选模型列表，经 ModelRoutingExecutor 按优先级逐模型尝试，
     * 失败时自动 fallback 到下一个候选模型。
     *
     * @param request 聊天请求
     * @return 模型返回的完整回答
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat(ChatRequest request) {
        boolean hasImages = hasImageContent(request);
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        List<ModelTarget> targets = selector.selectChatCandidates(thinkingLevel > 0, hasImages);
        validateTargets(targets);

        // 记录请求日志
        String traceId = String.valueOf(System.currentTimeMillis());
        String modelName = targets.isEmpty() ? "unknown" : targets.get(0).candidate().getModel();
        StringBuilder reqLog = new StringBuilder();
        reqLog.append("=== AI Provider Request ===\n");
        reqLog.append("TraceId: ").append(traceId).append("\n");
        reqLog.append("Model: ").append(modelName).append("\n");
        reqLog.append("ThinkingLevel: ").append(thinkingLevel).append("\n");
        reqLog.append("Targets: ").append(targets.size()).append(" (");
        for (ModelTarget t : targets) {
            reqLog.append(t.candidate().getModel()).append(" ");
        }
        reqLog.append(")\n");
        if (request.getMessages() != null && !request.getMessages().isEmpty()) {
            ChatMessage last = request.getMessages().get(request.getMessages().size() - 1);
            String content = last.getContent() != null ? last.getContent() : "";
            if (content.length() > 500) content = content.substring(0, 500) + "...";
            reqLog.append("LastMsg: [").append(last.getRole()).append("] ").append(content).append("\n");
        }
        AiLogHolder.log(traceId, reqLog.toString());

        String response = executor.executeWithFallback(
                ModelCapability.CHAT,
                targets,
                target -> clientsByProvider.get(target.candidate().getProvider()),
                (client, target) -> client.chat(request, target)
        );

        // 记录响应日志
        if (response != null && !response.isEmpty()) {
            StringBuilder respLog = new StringBuilder();
            respLog.append("=== AI Provider Response ===\n");
            respLog.append("TraceId: ").append(traceId).append("\n");
            respLog.append("Type: sync\n");
            respLog.append("\n").append(response).append("\n");
            AiLogHolder.log(traceId, respLog.toString());
        }

        return response;
    }

    /**
     * 同步聊天（指定模型 ID）
     * <p>
     * modelId 为空时走默认路由；不为空时从候选列表中查找匹配模型，仍走健康检查与 fallback。
     *
     * @param request 聊天请求
     * @param modelId 指定模型 ID，为空时走默认路由
     * @return 模型返回的完整回答
     */
    @Override
    public String chat(ChatRequest request, String modelId) {
        if (!StringUtils.hasText(modelId)) {
            return chat(request);
        }
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        List<ModelTarget> targets = List.of(resolveTarget(modelId, thinkingLevel > 0));
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
    // 检查请求是否包含图片
    private boolean hasImageContent(ChatRequest request) {
        return request.getMessages() != null && request.getMessages().stream()
                .anyMatch(m -> m.getImageUrls() != null && !m.getImageUrls().isEmpty());
    }

    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        boolean hasImages = hasImageContent(request);
        List<ModelTarget> targets = selector.selectChatCandidates(
                (request.getThinkingLevel() != null ? request.getThinkingLevel() : 0) > 0, hasImages);
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

    // 校验候选模型列表是否非空且至少有一个配置了有效 API Key 的模型
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

    // 根据模型目标的提供商查找对应的 ChatClient，未找到时记录警告并返回 null
    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    // 所有候选模型均失败时，通过回调通知错误并构造统一的 RemoteException
    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    // 根据 modelId 从候选模型列表中查找匹配的模型目标，未找到时抛出异常
    private ModelTarget resolveTarget(String modelId, boolean deepThinking) {
        return selector.selectChatCandidates(deepThinking).stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Chat 模型不可用: " + modelId));
    }
}
