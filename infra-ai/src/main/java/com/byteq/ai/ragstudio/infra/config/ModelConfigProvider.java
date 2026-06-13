package com.byteq.ai.ragstudio.infra.config;

/**
 * 模型配置提供者接口
 * <p>
 * 定义获取运行时 AI 模型配置的抽象，由 bootstrap 模块的
 * {@code AiModelConfigCache} 实现（从数据库加载），
 * 供 infra-ai 模块的 {@link com.byteq.ai.ragstudio.infra.model.ModelSelector} 消费。
 * </p>
 * <p>
 * 这种设计避免了 infra-ai 直接依赖 bootstrap 模块，保持了模块间的单向依赖。
 * </p>
 */
public interface ModelConfigProvider {

    /**
     * 获取当前动态模型配置（全量快照）
     *
     * @return 动态模型配置，不为 null
     */
    DynamicModelConfig getConfig();
}
