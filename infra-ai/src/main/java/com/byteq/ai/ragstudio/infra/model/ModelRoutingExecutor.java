package com.byteq.ai.ragstudio.infra.model;

import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 模型路由执行器
 * <p>
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制。
 * 这是模型路由系统的核心编排组件，实现了"顺序尝试-失败降级"的路由策略。
 * </p>
 * <p>
 * <b>执行流程：</b>
 * <ol>
 *   <li>接收按优先级排序的候选模型列表（来自 {@link ModelSelector}）</li>
 *   <li>使用 {@code clientResolver} 解析每个目标对应的客户端实例</li>
 *   <li>通过 {@link ModelHealthStore#allowCall(String)} 检查模型健康状态，跳过已断开的模型</li>
 *   <li>调用模型并等待结果：成功则标记健康并返回，失败则标记异常并尝试下一个</li>
 *   <li>如果所有候选均失败，抛出 {@link RemoteException}</li>
 * </ol>
 * </p>
 *
 * @param <C> 客户端类型，表示用于调用模型的客户端实例
 * @param <T> 返回值类型，表示模型调用后的返回结果
 * @author byteq
 * @see ModelSelector
 * @see ModelHealthStore
 * @see ModelCaller
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    private final ModelHealthStore healthStore;

    /**
     * 所有候选模型均失败时的回调（传入尝试过的目标列表）
     * <p>用于告警系统感知全失败事件</p>
     */
    private Consumer<List<ModelTarget>> onAllFailedCallback;

    /**
     * 设置全部失败回调
     */
    public void setOnAllFailedCallback(Consumer<List<ModelTarget>> callback) {
        this.onAllFailedCallback = callback;
    }

    /**
     * 带故障转移的模型调用执行
     * <p>
     * 按优先级顺序遍历候选模型列表，依次尝试调用直到成功为止。
     * 每个模型调用前会检查健康状态，调用后根据结果更新健康记录。
     * </p>
     *
     * @param capability     模型能力类型（如 EMBEDDING、RERANK、CHAT），用于日志和错误提示
     * @param targets        按优先级排序的候选模型目标列表
     * @param clientResolver 从模型目标解析出对应客户端实例的函数
     * @param caller         实际执行模型调用的函数式接口
     * @param <C>            客户端类型
     * @param <T>            返回值类型
     * @return 模型调用的结果
     * @throws RemoteException 当所有候选模型均不可用或全部调用失败时抛出
     */
    public <C, T> T executeWithFallback(
            // 模型能力类型(聊天、向量化、重排序)
            ModelCapability capability,
            // 候选模型列表
            List<ModelTarget> targets,
            // 对应客户端
            Function<ModelTarget, C> clientResolver,
            // 模型调用接口
            ModelCaller<C, T> caller)
    {
        String label = capability.getDisplayName();
        // 检查是否有可用的候选模型列表
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        // 按优先级顺序依次尝试每个候选模型
        for (ModelTarget target : targets) {
            C client = clientResolver.apply(target);
            // 如果找不到对应的客户端实现（如未注册的提供商），跳过当前候选
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            // 检查断路器状态，如果模型当前不可用则跳过
            if (!healthStore.allowCall(target.id())) {
                log.debug("{} model skipped by circuit breaker: modelId={}, provider={}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }

            try {
                // 执行实际的模型调用
                T response = caller.call(client, target);
                // 调用成功：标记健康状态，返回结果
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                // 调用失败：记录异常信息，标记失败状态，继续尝试下一个候选
                last = e;
                healthStore.markFailure(target.id());
                log.error("{} model failed, fallback to next. modelId={}, provider={}, error={}",
                        label, target.id(), target.candidate().getProvider(), e.getMessage());
            }
        }

        // 所有候选模型均失败，通知告警系统
        if (onAllFailedCallback != null) {
            onAllFailedCallback.accept(targets);
        }
        // 所有候选模型均失败，抛出异常
        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
