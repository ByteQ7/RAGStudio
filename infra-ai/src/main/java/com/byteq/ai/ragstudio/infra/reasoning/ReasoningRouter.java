package com.byteq.ai.ragstudio.infra.reasoning;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 推理深度路由入口
 * <p>
 * 对外门面：根据模型 ID 和用户设定的推理深度 (0–100)，
 * 自动选择适配策略并返回该模型原生的 API 参数。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReasoningRouter {

    private final ModelReasoningRegistry registry;
    private final ReasoningAdapter adapter;

    /**
     * 路由推理参数
     *
     * @param modelId   当前选中的模型 ID
     * @param rawLevel  用户设定的推理深度 (0–100)，0 表示关闭
     * @return 需注入到 API 请求体中的参数 Map
     */
    public Map<String, Object> route(String modelId, int rawLevel) {
        ReasoningConfig config = registry.getConfig(modelId);
        Map<String, Object> params = adapter.adapt(rawLevel, config);

        if (!params.isEmpty()) {
            log.debug("推理参数路由: model={}, level={}, type={}, params={}",
                    modelId, rawLevel, config.getType(), params);
        }

        return params;
    }

    /**
     * 判断指定的模型在当前推理深度下是否需要启用思考模式
     */
    public boolean isThinkingEnabled(String modelId, int rawLevel) {
        if (rawLevel <= 0) return false;
        ReasoningConfig config = registry.getConfig(modelId);
        return config.getType() != ReasoningType.NONE;
    }
}
