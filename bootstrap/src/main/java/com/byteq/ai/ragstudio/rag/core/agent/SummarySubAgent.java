package com.byteq.ai.ragstudio.rag.core.agent;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.CONVERSATION_SUMMARY_PROMPT_PATH;

/**
 * 对话摘要 Agent（A2A 架构）
 * <p>
 * 将多轮对话浓缩为话题导向的摘要，帮助后续问答理解上下文。
 * </p>
 *
 * <h3>输入 Task 参数</h3>
 * <ul>
 *   <li>{@code history} (String) — 原始对话历史文本</li>
 *   <li>{@code max_chars} (int, 可选) — 摘要最大字符数</li>
 * </ul>
 *
 * <h3>产出 Artifact</h3>
 * <ul>
 *   <li>{@code type="summary"} — 摘要文本</li>
 * </ul>
 */
@Slf4j
public class SummarySubAgent implements SubAgent {

    private static final AgentCard CARD = new AgentCard(
            "summary",
            "将多轮对话浓缩为话题导向的摘要，提取讨论主题、处理状态和关键约束。",
            List.of("conversation_summary")
    );

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    public SummarySubAgent(LLMService llmService, PromptTemplateLoader promptTemplateLoader) {
        this.llmService = llmService;
        this.promptTemplateLoader = promptTemplateLoader;
    }

    @Override
    public AgentCard getCard() { return CARD; }

    @Override
    public List<Artifact> run(Task task, AgentContext ctx, StreamCallback callback) {
        String history = task.getInputString("history");
        if (history == null || history.isBlank()) {
            return List.of(new Artifact(task.getId(), CARD.name(), "summary", ""));
        }

        int maxChars = 500;
        Object maxCharsObj = task.getInput().get("max_chars");
        if (maxCharsObj instanceof Number n) {
            maxChars = n.intValue();
        }

        try {
            String prompt = promptTemplateLoader.render(
                    CONVERSATION_SUMMARY_PROMPT_PATH,
                    Map.of(
                            "summary_max_chars", String.valueOf(maxChars)
                    )
            );

            // 将历史对话附加在 prompt 后
            String fullPrompt = prompt + "\n\n# 对话历史\n" + history;

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(fullPrompt)))
                    .temperature(0.3D)
                    .thinkingLevel(0)
                    .build();
            String summary = llmService.chat(request);
            if (summary == null || summary.isBlank()) {
                summary = "";
            }
            return List.of(new Artifact(task.getId(), CARD.name(), "summary", summary.trim()));
        } catch (Exception e) {
            log.warn("Summary Agent 生成失败", e);
            return List.of(new Artifact(task.getId(), CARD.name(), "summary", ""));
        }
    }
}
