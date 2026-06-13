package com.byteq.ai.ragstudio.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.config.ModelRoutingProperties;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 * <p>
 * 实现 {@link LLMService} 接口，提供基于健康检查和首包探测的智能路由能力。
 * 同步调用通过 {@link ModelRoutingExecutor} 执行带 fallback 的模型调用，
 * 流式调用采用"逐模型探测 + 自动切换"策略，确保高可用性。
 * <p>
 * 设计意图：
 * - 同步路由：通过 ModelSelector 获取候选模型列表，按优先级逐次尝试，
 *   失败时自动 fallback 到下一个候选模型
 * - 流式路由：创建 ProbeStreamBridge 代理回调，阻塞等待首包（TTFT），
 *   超时或失败时自动切换到下一个模型，成功后将缓冲内容放行给下游
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

    // === 错误消息常量 ===
    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String NO_MODEL_OR_APIKEY_MESSAGE = "未设置模型或API KEY，请检查";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    /** 深度思考模式下的首包超时倍数 */
    private static final int THINKING_TIMEOUT_MULTIPLIER = 2;

    /** 模型选择器：根据能力筛选候选模型 */
    private final ModelSelector selector;
    /** 模型健康状态存储 */
    private final ModelHealthStore healthStore;
    /** 带 fallback 的模型路由执行器（用于同步调用） */
    private final ModelRoutingExecutor executor;
    /** 首包探测组件（独立 Bean，确保 AOP 生效） */
    private final LlmFirstPacketProbe firstPacketProbe;
    /** 提供商 → ChatClient 实例映射 */
    private final Map<String, ChatClient> clientsByProvider;
    /** 模型路由配置属性 */
    private final ModelRoutingProperties routingProperties;

    /**
     * 构造 RoutingLLMService
     *
     * @param selector         模型选择器
     * @param healthStore      健康状态存储
     * @param executor         路由执行器
     * @param firstPacketProbe 首包探测组件
     * @param clients          所有 ChatClient 实现列表（按 provider 自动索引）
     * @param routingProperties 模型路由配置属性
     */
    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            LlmFirstPacketProbe firstPacketProbe,
            List<ChatClient> clients,
            ModelRoutingProperties routingProperties) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.firstPacketProbe = firstPacketProbe;
        this.routingProperties = routingProperties;
        // 按 provider 建立映射，供路由查找
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
     * 同步聊天（默认路由）
     * <p>
     * 通过 ModelSelector 获取候选模型列表，由 ModelRoutingExecutor 按优先级
     * 依次尝试调用，失败时自动 fallback 到下一个候选模型。
     * <p>
     * 整个过程被 @RagTraceNode 增强，在链路追踪中记录为 "LLM_ROUTING" 类型节点。
     *
     * @param request ChatRequest 请求
     * @return 模型返回的完整回答文本
     * @throws RemoteException 所有候选模型均调用失败时抛出
     */
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

    /**
     * 同步聊天（指定模型）
     * <p>
     * modelId 为空时等同于 chat(request)，走默认路由。
     * modelId 不为空时只使用指定模型，但仍经过健康检查。
     * 如果指定模型不可用，抛出 RemoteException（不会 fallback 到其他模型）。
     *
     * @param request ChatRequest 请求
     * @param modelId 指定的模型 ID，为空时走默认路由
     * @return 模型返回的完整回答文本
     * @throws RemoteException 指定模型不可用或调用失败时抛出
     */
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
     * 流式聊天（带路由 + 首包探测 + 自动 fallback）
     * <p>
     * 核心流程（逐模型探测）：
     * 1. 通过 ModelSelector 获取候选模型列表（支持 deep thinking 筛选）
     * 2. 遍历候选模型，跳过健康检查未通过或客户端缺失的模型
     * 3. 为当前模型创建 ProbeStreamBridge，代理流式回调
     * 4. 调用 client.streamChat 发起流式请求
     * 5. 阻塞等待首包结果（通过 LlmFirstPacketProbe 采集 TTFT trace）
     * 6. 首包成功 → 标记健康、返回取消句柄
     * 7. 首包失败/超时/无内容 → 标记不健康、取消当前连接、尝试下一个模型
     * 8. 所有模型均失败 → 回调 onError 并抛出 RemoteException
     * <p>
     * 设计意图：
     * - "首包探测"是区分"模型正在工作"和"模型挂了"的关键手段
     * - 只有收到首个 onContent/onThinking 才算探测成功
     * - 探测成功前的事件被 ProbeStreamBridge 缓冲，成功后一次性放行
     * - 这种设计避免了"连接已建立但模型不输出"的假成功场景
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
            // 跳过未配置 API Key 的提供商（NOOP 除外）
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
            // 健康检查：跳过被标记为不健康的模型
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            // 创建首包探测桥接器，代理下游回调
            ProbeStreamBridge bridge = new ProbeStreamBridge(callback);

            StreamCancellationHandle handle;
            try {
                // 发起流式请求
                handle = client.streamChat(request, bridge, target);
            } catch (Exception e) {
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            // 阻塞等待首包到达（TTFT 探测）
            ProbeStreamBridge.ProbeResult result = awaitFirstPacket(bridge, handle, callback, request);

            if (result.isSuccess()) {
                // 首包成功 → 标记健康，返回句柄（缓冲内容已放行）
                healthStore.markSuccess(target.id());
                return handle;
            }

            // 首包失败：标记不健康，取消当前连接，记录错误
            healthStore.markFailure(target.id());
            handle.cancel();

            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 所有候选模型均失败 → 通知客户端
        throw notifyAllFailed(callback, lastError);
    }

    /**
     * 校验候选模型列表是否为空，并过滤掉未配置 API Key 的模型
     * <p>
     * 如果过滤后无可用候选模型，抛出 RemoteException 提示用户检查配置。
     *
     * @param targets 候选模型列表
     * @throws RemoteException 无可用模型或 API Key 未配置时抛出
     */
    private void validateTargets(List<ModelTarget> targets) {
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(NO_MODEL_OR_APIKEY_MESSAGE);
        }
        // 过滤掉未配置 API Key 的提供商（NOOP 除外）
        boolean hasValidTarget = targets.stream()
                .anyMatch(t -> t.provider() != null
                        && (ModelProvider.NOOP.matches(t.candidate().getProvider())
                            || StringUtils.hasText(t.provider().getApiKey())));
        if (!hasValidTarget) {
            throw new RemoteException(NO_MODEL_OR_APIKEY_MESSAGE);
        }
    }

    /**
     * 按提供商查找 ChatClient 实例
     *
     * @param target 目标模型配置
     * @param label  日志标签（用于日志输出）
     * @return ChatClient 实例，未找到对应提供商时返回 null
     */
    private ChatClient resolveClient(ModelTarget target, String label) {
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    /**
     * 阻塞等待首包探测结果
     * <p>
     * 通过 LlmFirstPacketProbe 代理调用，确保 @RagTraceNode AOP 生效。
     * 超时时间从 {@link ModelRoutingProperties.Stream#getFirstPacketTimeoutSeconds()} 读取，
     * 深度思考（thinking）模式下使用 2 倍超时。
     * <p>
     * 如果等待线程被中断，会：
     * 1. 恢复中断标志
     * 2. 取消当前流式请求
     * 3. 通知下游回调 onError
     * 4. 抛出 RemoteException
     *
     * @param bridge   ProbeStreamBridge 实例
     * @param handle   取消句柄（中断时用于取消）
     * @param callback 流式回调（中断时用于通知错误）
     * @param request  聊天请求（用于判断 thinking 模式以调整超时）
     * @return 首包探测结果
     * @throws RemoteException 等待线程被中断时抛出
     */
    private ProbeStreamBridge.ProbeResult awaitFirstPacket(ProbeStreamBridge bridge,
                                                           StreamCancellationHandle handle,
                                                           StreamCallback callback,
                                                           ChatRequest request) {
        int baseTimeout = routingProperties.getStream().getFirstPacketTimeoutSeconds();
        int timeoutSeconds = Boolean.TRUE.equals(request.getThinking())
                ? baseTimeout * THINKING_TIMEOUT_MULTIPLIER
                : baseTimeout;
        try {
            return firstPacketProbe.awaitFirstPacket(bridge, timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // 恢复中断标志
            Thread.currentThread().interrupt();
            // 取消当前流式请求
            handle.cancel();
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    /**
     * 根据探测结果构建异常并记录日志
     * <p>
     * 将 ProbeResult 的不同类型（ERROR / TIMEOUT / NO_CONTENT）映射为
     * 对应的 RemoteException 和日志消息，供后续 fallback 决策使用。
     *
     * @param result 首包探测结果
     * @param target 失败的模型目标
     * @param label  日志标签
     * @return 构建的异常对象
     */
    private Throwable buildLastErrorAndLog(ProbeStreamBridge.ProbeResult result, ModelTarget target, String label) {
        switch (result.getType()) {
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    /**
     * 通知客户端所有模型均失败
     * <p>
     * 回调 callback.onError(finalException) 通知上游，
     * 并抛出 RemoteException 供调用方感知。
     *
     * @param callback  流式回调
     * @param lastError 最后一个模型的错误
     * @return RemoteException 异常
     */
    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    /**
     * 根据 modelId 解析 ModelTarget
     * <p>
     * 从 ModelSelector 的候选列表中查找匹配的模型目标。
     * 如果指定 modelId 不在候选列表中，抛出异常。
     *
     * @param modelId      模型 ID
     * @param deepThinking 是否需要深度思考能力
     * @return 匹配的 ModelTarget
     * @throws RemoteException 指定模型不可用时抛出
     */
    private ModelTarget resolveTarget(String modelId, boolean deepThinking) {
        return selector.selectChatCandidates(deepThinking).stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Chat 模型不可用: " + modelId));
    }
}
