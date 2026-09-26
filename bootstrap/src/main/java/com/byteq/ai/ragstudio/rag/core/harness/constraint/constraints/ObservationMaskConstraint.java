package com.byteq.ai.ragstudio.rag.core.harness.constraint.constraints;

import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import com.byteq.ai.ragstudio.rag.core.harness.constraint.AgentConstraint;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import org.springframework.stereotype.Component;

/**
 * 观察压缩说明：告知模型旧工具结果已被压缩为「结论 + 句柄」，
 * 结论够用时不回读，需要原文时用 observation_reader 按需读取。
 */
@Component
public class ObservationMaskConstraint implements AgentConstraint {

    private final ObservationMaskProperties properties;
    private final PromptTemplateLoader templateLoader;

    public ObservationMaskConstraint(ObservationMaskProperties properties,
                                     PromptTemplateLoader templateLoader) {
        this.properties = properties;
        this.templateLoader = templateLoader;
    }

    @Override
    public String id() {
        return "observation-mask";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public boolean isActive(AgentContext ctx, boolean hasRagSearch) {
        return Boolean.TRUE.equals(properties.getEnabled());
    }

    @Override
    public String render() {
        return templateLoader.loadSection("prompt/agent-reminder.st", "observation_mask");
    }
}
