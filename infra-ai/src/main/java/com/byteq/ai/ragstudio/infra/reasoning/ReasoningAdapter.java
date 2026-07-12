package com.byteq.ai.ragstudio.infra.reasoning;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 推理深度适配器
 * <p>
 * 将前端统一的 0–100 滑块值，按模型注册表中定义的映射规则，
 * 转换为各模型原生的 API 参数。
 * </p>
 * <p>
 * 核心流水线：
 * rawValue (0–100) → ① 零值守卫 → ② 归一化(0.0–1.0) → ③ 按类型分发 → API 参数
 * </p>
 */
@Slf4j
@Component
public class ReasoningAdapter {

    /**
     * 将前端滑块值适配为指定模型的 API 参数
     *
     * @param rawLevel 前端滑块值，0–100
     * @param config   模型的推理能力配置
     * @return API 参数字典（需合并到请求体中）
     */
    public Map<String, Object> adapt(int rawLevel, ReasoningConfig config) {
        // ① 零值守卫：直接返回关闭参数
        if (rawLevel <= 0 || config == null || config.getType() == ReasoningType.NONE) {
            return buildDisableParams(config);
        }

        // ② 归一化
        double progress = Math.min(rawLevel / 100.0, 1.0);

        // ③ 按类型分发
        switch (config.getType()) {
            case CONTINUOUS:
                return adaptContinuous(progress, config);
            case DISCRETE:
                return adaptDiscrete(progress, config);
            case BOOLEAN:
                return adaptBoolean(config);
            default:
                return new HashMap<>();
        }
    }

    /**
     * 连续型适配：线性插值
     * budget = min + (max - min) × progress
     * budget = ceil(budget / step) × step   // 向步长对齐
     * budget = clamp(budget, min, max)       // 边界保护
     */
    private Map<String, Object> adaptContinuous(double progress, ReasoningConfig config) {
        ReasoningConfig.ContinuousConfig cc = config.getContinuous();
        if (cc == null) return new HashMap<>();

        double budget = cc.getMinTokens() + (cc.getMaxTokens() - cc.getMinTokens()) * progress;
        // 向步长对齐
        if (cc.getStep() > 1) {
            budget = Math.ceil(budget / cc.getStep()) * cc.getStep();
        }
        // 边界保护
        budget = Math.max(cc.getMinTokens(), Math.min(budget, cc.getMaxTokens()));

        Map<String, Object> params = new HashMap<>();
        params.put("budget_tokens", (int) budget);
        return params;
    }

    /**
     * 离散型适配：阈值查找
     * 遍历 thresholds，找到首个 progress ≤ threshold.value 的项
     */
    private Map<String, Object> adaptDiscrete(double progress, ReasoningConfig config) {
        ReasoningConfig.DiscreteConfig dc = config.getDiscrete();
        if (dc == null || dc.getThresholds() == null || dc.getThresholds().isEmpty()) {
            return new HashMap<>();
        }

        // 默认取最高挡
        Map<String, Object> selected = dc.getThresholds().get(dc.getThresholds().size() - 1).getParams();

        for (ReasoningConfig.ThresholdItem item : dc.getThresholds()) {
            if (progress <= item.getValue()) {
                selected = item.getParams();
                break;
            }
        }

        return selected != null ? new HashMap<>(selected) : new HashMap<>();
    }

    /**
     * 布尔型适配：progress > 0 → 开启
     * 支持两种格式：
     * - "enable_thinking" → {"enable_thinking": true}
     * - "thinking.type"   → {"thinking": {"type": "enabled"}, "reasoning_effort": "high"}
     */
    private Map<String, Object> adaptBoolean(ReasoningConfig config) {
        String param = config.getBooleanParam() != null ? config.getBooleanParam() : "enable_thinking";
        Map<String, Object> params = new HashMap<>();
        if ("thinking.type".equals(param)) {
            params.put("thinking", Map.of("type", "enabled"));
            params.put("reasoning_effort", "high");
        } else {
            params.put(param, true);
        }
        return params;
    }

    /**
     * 构建关闭推理的参数
     */
    private Map<String, Object> buildDisableParams(ReasoningConfig config) {
        if (config == null) return new HashMap<>();

        switch (config.getType()) {
            case CONTINUOUS:
                return Map.of("thinking", Map.of("type", "disabled"));
            case DISCRETE:
                return Map.of("enable_thinking", false);
            case BOOLEAN:
                String param = config.getBooleanParam() != null ? config.getBooleanParam() : "enable_thinking";
                if ("thinking.type".equals(param)) {
                    return Map.of("thinking", Map.of("type", "disabled"));
                }
                return Map.of(param, false);
            default:
                return new HashMap<>();
        }
    }
}
