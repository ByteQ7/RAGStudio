package com.byteq.ai.ragstudio.rag.core.harness.constraint.constraints;

import com.byteq.ai.ragstudio.rag.core.harness.constraint.AgentConstraint;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import org.springframework.stereotype.Component;

/**
 * 多轮对话提醒：历史消息达到一定规模时，提醒模型审阅前序对话、必要时先给出 Plan。
 */
@Component
public class MultiTurnConstraint implements AgentConstraint {

    /** 触发阈值：历史消息条数（含摘要 SYSTEM） */
    private static final int MIN_HISTORY_SIZE = 4;

    private final PromptTemplateLoader templateLoader;

    public MultiTurnConstraint(PromptTemplateLoader templateLoader) {
        this.templateLoader = templateLoader;
    }

    @Override
    public String id() {
        return "multi-turn";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public boolean isActive(AgentContext ctx, boolean hasRagSearch) {
        return ctx.getHistory() != null && ctx.getHistory().size() >= MIN_HISTORY_SIZE;
    }

    @Override
    public String render() {
        return templateLoader.loadSection("prompt/agent-reminder.st", "multi_turn");
    }
}
