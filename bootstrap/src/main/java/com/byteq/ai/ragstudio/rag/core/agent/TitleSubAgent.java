package com.byteq.ai.ragstudio.rag.core.agent;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.CONVERSATION_TITLE_PROMPT_PATH;

/**
 * 标题生成 Agent（A2A 架构）
 * <p>
 * 根据用户问题生成会话标题。包装现有的 {@code ConversationTitleGenerator} 逻辑。
 * </p>
 *
 * <h3>输入 Task 参数</h3>
 * <ul>
 *   <li>{@code question} (String) — 用户问题</li>
 * </ul>
 *
 * <h3>产出 Artifact</h3>
 * <ul>
 *   <li>{@code type="title"} — 生成的标题文本</li>
 * </ul>
 */
@Slf4j
public class TitleSubAgent implements SubAgent {

    private static final AgentCard CARD = new AgentCard(
            "title",
            "根据用户的问题生成简短、准确的会话标题。",
            List.of("title_generation")
    );

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    public TitleSubAgent(LLMService llmService, PromptTemplateLoader promptTemplateLoader) {
        this.llmService = llmService;
        this.promptTemplateLoader = promptTemplateLoader;
    }

    @Override
    public AgentCard getCard() { return CARD; }

    @Override
    public List<Artifact> run(Task task, AgentContext ctx, StreamCallback callback) {
        String question = task.getInputString("question");
        if (question == null || question.isBlank()) {
            return List.of(new Artifact(task.getId(), CARD.name(), "title", "新对话"));
        }

        String prompt = promptTemplateLoader.render(
                CONVERSATION_TITLE_PROMPT_PATH,
                Map.of(
                        "title_max_chars", "30",
                        "question", question
                )
        );

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.7D)
                    .topP(0.3D)
                    .thinkingLevel(0)
                    .build();
            String title = llmService.chat(request);
            if (title == null || title.isBlank()) {
                title = "新对话";
            }
            return List.of(new Artifact(task.getId(), CARD.name(), "title", title.trim()));
        } catch (Exception e) {
            log.warn("Title Agent 生成失败", e);
            return List.of(new Artifact(task.getId(), CARD.name(), "title", "新对话"));
        }
    }
}
