package com.byteq.ai.ragstudio.infra.reasoning;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 模型推理能力配置
 * <p>描述某个模型支持的推理深度参数映射规则。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningConfig {
    /** 推理能力类型 */
    private ReasoningType type;

    /** 连续型配置（type=CONTINUOUS 时使用） */
    private ContinuousConfig continuous;

    /** 离散型配置（type=DISCRETE 时使用） */
    private DiscreteConfig discrete;

    /** 布尔型/关闭时使用的参数字段名（type=BOOLEAN 时使用） */
    private String booleanParam;

    /**
     * 连续型配置
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContinuousConfig {
        private int minTokens;
        private int maxTokens;
        private int step;
    }

    /**
     * 离散型配置——阈值列表
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscreteConfig {
        private List<ThresholdItem> thresholds;
    }

    /**
     * 离散阈值项
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThresholdItem {
        /** 归一化阈值 (0.0–1.0)，当 progress ≤ 此值时匹配 */
        private double value;
        /** 匹配时使用的 API 参数 */
        private Map<String, Object> params;
    }
}
