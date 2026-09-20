package com.byteq.ai.ragstudio.rag.core.harness.prompt;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.rag.core.harness.constraint.AgentConstraint;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * System Prompt 组装器（Harness · 提示词子模块）
 * <p>
 * 把 System Prompt 的拼装从执行器中抽出，形成固定管线：
 * <ol>
 *   <li>基础模板槽位填充（工具清单 / KB 上下文 / 相关性提示 / 检索优先规则）</li>
 *   <li>对话目标摘要（{@code agent-reminder.st} 的 goal_summary section）</li>
 *   <li>历史中的 SYSTEM 消息（会话摘要、分组指令等，AgentScope 不允许输入 SYSTEM 消息）</li>
 *   <li>生效中的行为约束（{@link AgentConstraint}，多轮提醒 / 历史图片 / 强制检索）</li>
 * </ol>
 * 动态上下文块（工作流候选等）不在这里拼装——由
 * {@code DynamicContextMiddleware} 在每轮模型调用前追加，支持"用了就清除"。
 */
@Slf4j
@Component
public class SystemPromptAssembler {

    private static final String REACT_SYSTEM_PROMPT_PATH = "prompt/react-system-agentscope.st";
    private static final String AGENT_REMINDER_PATH = "prompt/agent-reminder.st";

    private static final String NO_TOOLS_TEXT = "当前没有可用工具。";
    private static final String NO_KB_TEXT = "（无预检索知识库内容）";
    private static final String KB_IRRELEVANT_NOTE =
            "> ⚠️ 注意：用户问题经判断与所选知识库**不相关**，已跳过知识库检索。请不要尝试使用 rag_search 工具，"
            + "也不要输出任何 [^chunk_{id}] 引用标记。";
    private static final String SEARCH_PRIORITY_WITH_RAG = "先 `rag_search`，不够再 `web-search` 或其他";
    private static final String SEARCH_PRIORITY_WITHOUT_RAG = "使用可用工具搜索相关数据";

    private final PromptTemplateLoader templateLoader;
    private final List<AgentConstraint> constraints;

    public SystemPromptAssembler(PromptTemplateLoader templateLoader,
                                 List<AgentConstraint> constraints) {
        this.templateLoader = templateLoader;
        this.constraints = constraints != null ? constraints : List.of();
    }

    /**
     * 组装静态 System Prompt
     *
     * @param ctx       Agent 上下文
     * @param toolNames 本次注册的规范化工具名
     * @return system prompt 文本（动态上下文块由中间件在每轮追加）
     */
    public String assemble(AgentContext ctx, List<String> toolNames) {
        String template = templateLoader.load(REACT_SYSTEM_PROMPT_PATH);

        String toolDefs;
        if (CollUtil.isEmpty(toolNames)) {
            toolDefs = NO_TOOLS_TEXT;
        } else {
            toolDefs = "当前可用工具：" + String.join("、", toolNames) + "。";
        }

        String kbContext = StrUtil.isNotBlank(ctx.getKbContext()) ? ctx.getKbContext() : NO_KB_TEXT;
        boolean hasRagSearch = toolNames != null && toolNames.contains("rag_search");
        // 「已选知识库且相关，必须强制检索」的强指令统一由 KbForcedConstraint 下发
        //（可在后管「提示词管理」页编辑），此处只负责"不相关"场景的负向提示，避免同一约束双份注入
        String relevanceNote = (!ctx.isKbRelevant() && StrUtil.isBlank(ctx.getKbContext()))
                ? KB_IRRELEVANT_NOTE : "";
        String searchPriorityRule = hasRagSearch ? SEARCH_PRIORITY_WITH_RAG : SEARCH_PRIORITY_WITHOUT_RAG;

        String filled = PromptTemplateUtils.fillSlots(template, Map.of(
                "tool_definitions", toolDefs,
                "kb_context", kbContext,
                "kb_relevance_note", relevanceNote,
                "search_priority_rule", searchPriorityRule
        ));
        String sysPrompt = PromptTemplateUtils.cleanupPrompt(filled);

        // 1. 对话目标摘要
        String goalSummary = buildGoalSummary(ctx);
        if (goalSummary != null) {
            sysPrompt = sysPrompt + "\n\n" + goalSummary;
        }

        // 2. 对话历史中的 SYSTEM 消息（摘要等）合并进系统提示词
        if (CollUtil.isNotEmpty(ctx.getHistory())) {
            StringBuilder extras = new StringBuilder();
            for (ChatMessage msg : ctx.getHistory()) {
                if (msg.getRole() == ChatMessage.Role.SYSTEM && StrUtil.isNotBlank(msg.getContent())) {
                    extras.append("\n\n").append(msg.getContent());
                }
            }
            if (extras.length() > 0) {
                sysPrompt = sysPrompt + extras;
            }
        }

        // 3. 生效中的约束（按 order 排序，逐条追加）
        List<AgentConstraint> active = new ArrayList<>();
        for (AgentConstraint constraint : constraints) {
            try {
                if (constraint.isActive(ctx, hasRagSearch)) {
                    active.add(constraint);
                }
            } catch (Exception e) {
                log.warn("约束 [{}] 生效判断失败，跳过: {}", constraint.id(), e.getMessage());
            }
        }
        active.sort(Comparator.comparingInt(AgentConstraint::order));
        for (AgentConstraint constraint : active) {
            try {
                String text = constraint.render();
                if (StrUtil.isNotBlank(text)) {
                    sysPrompt = sysPrompt + "\n\n" + text;
                }
            } catch (Exception e) {
                log.warn("约束 [{}] 渲染失败，跳过: {}", constraint.id(), e.getMessage());
            }
        }

        return sysPrompt;
    }

    /**
     * 构建对话目标摘要：从历史中提取用户问题链（A → B），
     * 图片问题的下一条 assistant 分析结果作为图片说明。
     */
    private String buildGoalSummary(AgentContext ctx) {
        if (CollUtil.isEmpty(ctx.getHistory())) {
            return null;
        }
        List<String> userQuestions = new ArrayList<>();
        List<String> imageDescriptions = new ArrayList<>();
        for (int i = 0; i < ctx.getHistory().size(); i++) {
            ChatMessage msg = ctx.getHistory().get(i);
            boolean hasImage = msg.getImageUrls() != null && !msg.getImageUrls().isEmpty();
            if (msg.getRole() == ChatMessage.Role.USER && StrUtil.isNotBlank(msg.getContent())) {
                String content = msg.getContent().trim();
                if (!content.startsWith("Observation:") && !content.startsWith("{\"query\"")
                        && !content.contains("[^chunk_")) {
                    userQuestions.add(content);
                    if (hasImage && i + 1 < ctx.getHistory().size()) {
                        ChatMessage next = ctx.getHistory().get(i + 1);
                        if (next.getRole() == ChatMessage.Role.ASSISTANT && StrUtil.isNotBlank(next.getContent())) {
                            imageDescriptions.add(next.getContent().trim());
                        }
                    }
                }
            }
        }
        if (userQuestions.isEmpty() && imageDescriptions.isEmpty()) {
            return null;
        }
        String previousQuestions = String.join(" → ", userQuestions);
        String imageNote = "";
        if (!imageDescriptions.isEmpty()) {
            imageNote = "（用户之前上传了图片，分析结果：" + String.join("；", imageDescriptions) + "）。";
        }
        return templateLoader.renderSection(AGENT_REMINDER_PATH, "goal_summary", Map.of(
                "previous_questions", previousQuestions,
                "image_note", imageNote
        ));
    }
}
