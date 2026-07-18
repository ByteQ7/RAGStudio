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
 * <p>声明式定义每个模型的推理能力类型及映射参数。</p>
 *
 * <h3>支持的推理类型</h3>
 * <ul>
 *   <li>{@link ReasoningType#CONTINUOUS} — 连续预算型（Claude / Gemini）：通过 budget_tokens 线性控制</li>
 *   <li>{@link ReasoningType#DISCRETE} — 离散档位型（OpenAI o-series）：low / medium / high</li>
 *   <li>{@link ReasoningType#BOOLEAN} — 开关型（DeepSeek / 国内模型）：仅开启/关闭</li>
 *   <li>{@link ReasoningType#NONE} — 不支持推理</li>
 * </ul>
 *
 * <h3>参数注入路径</h3>
 * <ul>
 *   <li>DeepSeek 等 → 原始 OkHttp 路径（buildRequestBody），所有类型参数均可注入</li>
 *   <li>OpenAI 等 → LangChain4j 标准路径（OpenAiChatRequestParameters），仅支持 reasoning_effort</li>
 * </ul>
 */
@Slf4j
@Component
public class ModelReasoningRegistry {

    @Getter
    private final Map<String, ReasoningConfig> registry = new HashMap<>();

    @PostConstruct
    public void init() {
        // ==================== 连续预算型 (CONTINUOUS) ====================
        // Claude / Gemini 等通过 budget_tokens 线性控制思考深度

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

        // ==================== 离散档位型 (DISCRETE) ====================
        // OpenAI o-series / deepseek-reasoner 使用 reasoning_effort

        register("o3-mini", new ReasoningConfig(
                ReasoningType.DISCRETE, null,
                new ReasoningConfig.DiscreteConfig(List.of(
                        new ReasoningConfig.ThresholdItem(0.0, Map.of("reasoning_effort", "low")),
                        new ReasoningConfig.ThresholdItem(0.5, Map.of("reasoning_effort", "medium")),
                        new ReasoningConfig.ThresholdItem(1.0, Map.of("reasoning_effort", "high"))
                )), null
        ));

        register("o1", new ReasoningConfig(
                ReasoningType.DISCRETE, null,
                new ReasoningConfig.DiscreteConfig(List.of(
                        new ReasoningConfig.ThresholdItem(0.0, Map.of("reasoning_effort", "low")),
                        new ReasoningConfig.ThresholdItem(0.5, Map.of("reasoning_effort", "medium")),
                        new ReasoningConfig.ThresholdItem(1.0, Map.of("reasoning_effort", "high"))
                )), null
        ));

        register("o3", new ReasoningConfig(
                ReasoningType.DISCRETE, null,
                new ReasoningConfig.DiscreteConfig(List.of(
                        new ReasoningConfig.ThresholdItem(0.0, Map.of("reasoning_effort", "low")),
                        new ReasoningConfig.ThresholdItem(0.5, Map.of("reasoning_effort", "medium")),
                        new ReasoningConfig.ThresholdItem(1.0, Map.of("reasoning_effort", "high"))
                )), null
        ));

        // ==================== 开关型-DeepSeek (BOOLEAN) ====================
        // DeepSeek API: {"thinking": {"type": "enabled"}} + reasoning_effort

        ReasoningConfig deepseekConfig = new ReasoningConfig(
                ReasoningType.BOOLEAN, null, null, "thinking.type");

        register("deepseek-r1", deepseekConfig);
        register("deepseek-v4-flash", deepseekConfig);
        register("deepseek-v4-pro", deepseekConfig);
        register("deepseek-chat", deepseekConfig);
        register("deepseek-reasoner", deepseekConfig);

        // ==================== 开关型-国内模型 (BOOLEAN) ====================
        // 大多数国内模型使用 enable_thinking: true/false 或 reasoning_effort

        ReasoningConfig enableThinkingConfig = new ReasoningConfig(
                ReasoningType.BOOLEAN, null, null, "enable_thinking");

        register("qwen3-235b", enableThinkingConfig);
        register("qwen3-max", enableThinkingConfig);
        register("qwen3-vl-plus", enableThinkingConfig);
        register("qwen3-vl-flash", enableThinkingConfig);
        register("qwen-plus", enableThinkingConfig);
        register("glm-4", enableThinkingConfig);
        register("glm-4.7", enableThinkingConfig);
        register("kimi-k2", enableThinkingConfig);
        register("moonshot-v1-8k", enableThinkingConfig);
        register("minimax-text-01", enableThinkingConfig);
        register("yi-lightning", enableThinkingConfig);
        register("ernie-4.0", enableThinkingConfig);
        register("hunyuan-standard", enableThinkingConfig);
        register("step-2-16k", enableThinkingConfig);
        register("step-2", enableThinkingConfig);
        register("sensechat-5", enableThinkingConfig);

        // ==================== 不支持推理 (NONE) ====================

        register("gpt-4o", new ReasoningConfig(ReasoningType.NONE, null, null, null));
        register("gpt-4o-mini", new ReasoningConfig(ReasoningType.NONE, null, null, null));
        register("gpt-4o-vl", new ReasoningConfig(ReasoningType.NONE, null, null, null));
        register("text-embedding-3-small", new ReasoningConfig(ReasoningType.NONE, null, null, null));
        register("qwen-emb-8b", new ReasoningConfig(ReasoningType.NONE, null, null, null));
        register("qwen3-rerank", new ReasoningConfig(ReasoningType.NONE, null, null, null));

        log.info("已注册 {} 个模型的推理能力配置", registry.size());
    }

    public void register(String modelId, ReasoningConfig config) {
        registry.put(modelId, config);
    }

    /**
     * 获取指定模型的推理配置
     * <p>优先级：精确匹配 → 前缀兜底 → NONE</p>
     */
    public ReasoningConfig getConfig(String modelId) {
        ReasoningConfig config = registry.get(modelId);
        if (config != null) return config;

        String lower = modelId.toLowerCase();

        // DeepSeek 系列
        if (lower.startsWith("deepseek")) {
            config = registry.get("deepseek-chat");
            if (config != null) return config;
        }
        // Qwen 系列
        if (lower.startsWith("qwen")) {
            config = registry.get("qwen3-235b");
            if (config != null) return config;
        }
        // GLM 系列
        if (lower.startsWith("glm")) {
            config = registry.get("glm-4");
            if (config != null) return config;
        }
        // Kimi / Moonshot 系列
        if (lower.startsWith("kimi") || lower.startsWith("moonshot")) {
            config = registry.get("kimi-k2");
            if (config != null) return config;
        }
        // Yi 系列
        if (lower.startsWith("yi")) {
            config = registry.get("yi-lightning");
            if (config != null) return config;
        }
        // ERNIE 系列
        if (lower.startsWith("ernie")) {
            config = registry.get("ernie-4.0");
            if (config != null) return config;
        }
        // Hunyuan 系列
        if (lower.startsWith("hunyuan")) {
            config = registry.get("hunyuan-standard");
            if (config != null) return config;
        }
        // Step 系列（阶跃星辰）
        if (lower.startsWith("step")) {
            config = registry.get("step-2");
            if (config != null) return config;
        }
        // SenseChat 系列（商汤）
        if (lower.startsWith("sensechat") || lower.startsWith("sense")) {
            config = registry.get("sensechat-5");
            if (config != null) return config;
        }
        // o-series（OpenAI o1/o3）
        if (lower.startsWith("o1") || lower.startsWith("o3")) {
            config = registry.get("o3-mini");
            if (config != null) return config;
        }
        // Claude 系列
        if (lower.startsWith("claude")) {
            config = registry.get("claude-sonnet-4");
            if (config != null) return config;
        }
        // Gemini 系列
        if (lower.startsWith("gemini")) {
            config = registry.get("gemini-2.5-flash");
            if (config != null) return config;
        }

        return new ReasoningConfig(ReasoningType.NONE, null, null, null);
    }

    public boolean supportsReasoning(String modelId) {
        ReasoningConfig config = getConfig(modelId);
        return config != null && config.getType() != ReasoningType.NONE;
    }
}
