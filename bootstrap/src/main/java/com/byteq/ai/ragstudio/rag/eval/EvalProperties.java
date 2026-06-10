package com.byteq.ai.ragstudio.rag.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评测模式配置
 * <p>
 * 用途：控制评测专用接口（/rag/eval/sync）和 AOP 切面是否启用
 * 生产环境默认 false，评测环境通过 -Dragstudio.eval.enabled=true 或独立 profile 开启
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.eval")
public class EvalProperties {

    /**
     * 是否启用评测模式
     * <p>
     * false（默认）：EvalController 和 EvalRetrievalCaptureAspect 不注册，零运行时开销
     * true：注册评测接口和切面，用于评测项目调用
     */
    private boolean enabled = false;
}
