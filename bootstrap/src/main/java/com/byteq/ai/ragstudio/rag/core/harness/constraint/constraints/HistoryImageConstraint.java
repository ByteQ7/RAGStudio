package com.byteq.ai.ragstudio.rag.core.harness.constraint.constraints;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.rag.core.harness.constraint.AgentConstraint;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import org.springframework.stereotype.Component;

/**
 * 历史图片参考提醒：对话历史中存在带图消息时，提醒模型结合历史图片回答。
 */
@Component
public class HistoryImageConstraint implements AgentConstraint {

    private final PromptTemplateLoader templateLoader;

    public HistoryImageConstraint(PromptTemplateLoader templateLoader) {
        this.templateLoader = templateLoader;
    }

    @Override
    public String id() {
        return "history-image";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean isActive(AgentContext ctx, boolean hasRagSearch) {
        if (ctx.getHistory() == null) {
            return false;
        }
        for (ChatMessage msg : ctx.getHistory()) {
            if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String render() {
        return templateLoader.loadSection("prompt/agent-reminder.st", "image_history");
    }
}
