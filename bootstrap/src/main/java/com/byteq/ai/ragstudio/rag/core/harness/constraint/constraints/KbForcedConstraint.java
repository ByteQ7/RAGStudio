package com.byteq.ai.ragstudio.rag.core.harness.constraint.constraints;

import com.byteq.ai.ragstudio.rag.core.harness.constraint.AgentConstraint;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import org.springframework.stereotype.Component;

/**
 * 强制检索约束：已选知识库且问题相关时，第一轮行动必须调用 rag_search。
 */
@Component
public class KbForcedConstraint implements AgentConstraint {

    private final PromptTemplateLoader templateLoader;

    public KbForcedConstraint(PromptTemplateLoader templateLoader) {
        this.templateLoader = templateLoader;
    }

    @Override
    public String id() {
        return "kb-forced";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public boolean isActive(AgentContext ctx, boolean hasRagSearch) {
        return ctx.isKbRelevant() && hasRagSearch;
    }

    @Override
    public String render() {
        return templateLoader.loadSection("prompt/agent-reminder.st", "kb_forced");
    }
}
