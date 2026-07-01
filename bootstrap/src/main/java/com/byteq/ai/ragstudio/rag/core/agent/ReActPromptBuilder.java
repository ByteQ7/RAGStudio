package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ReACT Prompt 构建器
 * <p>
 * 负责从模板文件加载 ReACT System Prompt，并填充运行时数据：
 * <ul>
 *   <li>工具列表（从 {@link ToolRegistry#formatForSystemPrompt()} 获取）</li>
 *   <li>预检索的知识库上下文</li>
 *   <li>知识库相关性说明</li>
 * </ul>
 * 构建结果是一个 {@link ChatMessage#system} 消息，直接注入 Agent 循环的消息列表头部。
 */
@Slf4j
@Component
public class ReActPromptBuilder {

    /** ReACT System Prompt 模板路径 */
    private static final String REACT_SYSTEM_PROMPT_PATH = "prompt/react-system.st";

    /** 无工具时的占位文本 */
    private static final String NO_TOOLS_TEXT = "当前没有可用工具。";

    /** 无知识库上下文时的占位文本 */
    private static final String NO_KB_TEXT = "（无预检索知识库内容）";

    /** KB 不相关时的提示 */
    private static final String KB_IRRELEVANT_NOTE =
            "> ⚠️ 注意：用户问题经判断与所选知识库**不相关**，已跳过知识库检索。请不要尝试使用 rag_search 工具。";

    /** KB 相关时的提示 */
    private static final String KB_RELEVANT_NOTE =
            "> ⚠️ 【强制指令】用户已选择知识库且问题与知识库相关。**你的第一轮行动必须调用 rag_search 工具检索知识库**，然后基于检索结果回答。不得仅凭自身知识直接回答。";

    private final PromptTemplateLoader templateLoader;

    public ReActPromptBuilder(PromptTemplateLoader templateLoader) {
        this.templateLoader = templateLoader;
    }

    /**
     * 构建 ReACT System Prompt
     *
     * @param toolRegistry 工具注册中心
     * @param kbContext    预检索的知识库上下文（为空时显示占位文本）
     * @param kbRelevant   知识库是否相关（仅当选了知识库但不相关时为 false）
     * @return system 角色的 ChatMessage
     */
    public ChatMessage build(ToolRegistry toolRegistry, String kbContext, boolean kbRelevant) {
        String template = templateLoader.load(REACT_SYSTEM_PROMPT_PATH);

        String toolDefs = toolRegistry != null && toolRegistry.size() > 0
                ? toolRegistry.formatForSystemPrompt()
                : NO_TOOLS_TEXT;

        String kbSection = StrUtil.isNotBlank(kbContext)
                ? kbContext
                : NO_KB_TEXT;

        String relevanceNote = "";
        if (!kbRelevant && StrUtil.isBlank(kbContext)) {
            relevanceNote = KB_IRRELEVANT_NOTE;
        } else if (kbRelevant && StrUtil.isBlank(kbContext)) {
            relevanceNote = KB_RELEVANT_NOTE;
        }

        String filled = PromptTemplateUtils.fillSlots(template, Map.of(
                "tool_definitions", toolDefs,
                "kb_context", kbSection,
                "kb_relevance_note", relevanceNote
        ));

        return ChatMessage.system(PromptTemplateUtils.cleanupPrompt(filled));
    }
}
