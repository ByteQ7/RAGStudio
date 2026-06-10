package com.byteq.ai.ragstudio.infra.model;

import com.byteq.ai.ragstudio.infra.config.AIModelProperties;

/**
 * 模型目标配置记录
 * <p>
 * 封装一次模型调用所需的所有目标信息，是模型路由选择链路的最终产出物。
 * 在模型选择流程中，{@link ModelSelector} 根据配置和健康状态筛选出可用的模型后，
 * 为每个可用模型创建一个 {@code ModelTarget} 实例，供 {@link ModelRoutingExecutor}
 * 在路由执行和故障转移时使用。
 * </p>
 * <p>
 * <b>包含的信息：</b>
 * <ul>
 *   <li>{@code id} —— 模型唯一标识符，用于健康状态追踪和日志记录</li>
 *   <li>{@code candidate} —— 模型候选配置，包含模型名称、提供商、维度等参数</li>
 *   <li>{@code provider} —— 提供商配置，包含 API Key、Endpoint URL 等连接信息</li>
 * </ul>
 * </p>
 *
 * @param id        模型唯一标识符，用于在 ModelHealthStore 中追踪健康状态
 * @param candidate 模型候选配置，包含模型的具体参数和设置（如 model name、dimension、priority 等）
 * @param provider  提供商配置，包含模型提供商的相关认证和连接信息
 * @author byteq
 * @see ModelSelector
 * @see ModelRoutingExecutor
 * @see ModelHealthStore
 */
public record ModelTarget(
        String id,
        AIModelProperties.ModelCandidate candidate,
        AIModelProperties.ProviderConfig provider
) {
}
