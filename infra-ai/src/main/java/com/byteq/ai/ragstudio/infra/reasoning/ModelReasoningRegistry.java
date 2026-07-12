package com.byteq.ai.ragstudio.infra.reasoning;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型推理能力注册表
 * <p>声明式定义每个模型的推理能力类型及映射参数。
 * 新增模型只需在此添加记录，核心适配逻辑零改动。</p>
 */
@Slf4j
@Component
public class ModelReasoningRegistry {

    @Getter
    private final Map<String, ReasoningConfig> registry = new HashMap<>();

    @PostConstruct
    public void init() {
        // ==================== 连续型 ====================

        register("claude-opus-4", new ReasoningConfig(
                ReasoningType.CONTINUOUS,
                new ReasoningConfig.ContinuousConfig(1024, 8192, 1),
                null, null
        ));

        register("claude-sonnet-4", new ReasoningConfig(
                ReasoningType.CONTINUOUS,
                new ReasoningConfig.ContinuousConfig(1024, 4096, 1),
                null, null
        ));

        register("gemini-2.5-pro", new ReasoningConfig(
                ReasoningType.CONTINUOUS,
                new ReasoningConfig.ContinuousConfig(128, 4096, 128),
                null, null
        ));

        register("gemini-2.5-flash", new ReasoningConfig(
                ReasoningType.CONTINUOUS,
                new ReasoningConfig.ContinuousConfig(128, 2048, 128),
                null, null
        ));

        // ==================== 离散型 ====================

        register("deepseek-r1", new ReasoningConfig(
                ReasoningType.DISCRETE,
                null,
                new ReasoningConfig.DiscreteConfig(List.of(
                        new ReasoningConfig.ThresholdItem(0.33, Map.of("effort", "low")),
                        new ReasoningConfig.ThresholdItem(0.66, Map.of("effort", "medium")),
                        new ReasoningConfig.ThresholdItem(1.0, Map.of("effort", "high"))
                )),
                null
        ));

        // ==================== 布尔型 ====================

        register("qwen3-235b", new ReasoningConfig(
                ReasoningType.BOOLEAN,
                null, null,
                "enable_thinking"
        ));

        // ==================== 不支持 ====================

        register("gpt-4o", new ReasoningConfig(
                ReasoningType.NONE, null, null, null
        ));

        register("gpt-4o-mini", new ReasoningConfig(
                ReasoningType.NONE, null, null, null
        ));

        log.info("已注册 {} 个模型的推理能力配置", registry.size());
    }

    /**
     * 注册一个模型的推理配置
     */
    public void register(String modelId, ReasoningConfig config) {
        registry.put(modelId, config);
    }

    /**
     * 获取指定模型的推理配置
     * @param modelId 模型标识
     * @return 推理配置，如果未注册则返回 NONE 类型的默认配置
     */
    public ReasoningConfig getConfig(String modelId) {
        return registry.getOrDefault(modelId,
                new ReasoningConfig(ReasoningType.NONE, null, null, null));
    }

    /**
     * 判断指定模型是否支持推理
     */
    public boolean supportsReasoning(String modelId) {
        ReasoningConfig config = registry.get(modelId);
        return config != null && config.getType() != ReasoningType.NONE;
    }
}
