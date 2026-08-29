package com.byteq.ai.ragstudio.rag.prompt.config;

import java.util.Arrays;
import java.util.Optional;

/**
 * 提示词 key 元数据注册表
 * <p>
 * 集中登记所有可被后管「提示词管理」页编辑的提示词。每个条目包含语义化 key、
 * 分类、显示名、用途说明、classpath 默认模板路径（可为空，表示默认内容在代码中）。
 * </p>
 * <p>
 * 读取策略：DB 快照（{@code t_prompt_config}）优先（enabled=true），
 * 缺失或禁用时回退 classpath 默认模板 / 代码内默认文案。
 * </p>
 */
public enum PromptKeys {

    // ==================== chat 对话问答类（Agent 主链路） ====================

    REACT_SYSTEM("react_system", "chat", "Agent 主链路 ReAct 系统提示词",
            "Agent 角色「小码」+ 工具调用规则 + 引用溯源 + 输出契约，是对话主链路的灵魂提示词。",
            "prompt/react-system-agentscope.st",
            "{tool_definitions},{kb_context},{kb_relevance_note},{search_priority_rule}"),

    AGENT_REMINDER("agent_reminder", "chat", "Agent 前置指令（多段 section）",
            "按场景追加的 Agent 前置指令，包含 multi_turn / image_history / kb_forced / goal_summary 等 section。",
            "prompt/agent-reminder.st",
            "{previous_questions},{image_note}"),

    ANSWER_CHAT("answer_chat", "chat", "统一 RAG 问答系统提示词（备用）",
            "非 Agent 场景下的统一问答 system（KB/MCP/混合），当前主链路已走 Agent，属备用路径。",
            "prompt/answer-chat.st",
            ""),

    ANSWER_CHAT_SYSTEM("answer_chat_system", "chat", "旧版助手问答系统提示词（预留）",
            "旧版企业助手「小码」问答 system，当前无调用方，预留保留。",
            "prompt/answer-chat-system.st",
            ""),

    CONTEXT_FORMAT("context_format", "chat", "上下文格式 section 模板",
            "检索上下文拼装格式，含 kb-section / snippet-rules / mcp-section / sub-question / evidence / summary 等多段 section。",
            "prompt/context-format.st",
            "{chunks_body},{rules},{body},{error_list},{index},{question},{context},{questions},{content},{snippet_section}"),

    // ==================== query 查询理解类 ====================

    QUERY_REWRITE("query_rewrite", "query", "查询改写与多问句拆分",
            "对用户问题进行改写并拆分为多个子问句（JSON 输出契约 + 示例），检索前处理。",
            "prompt/user-question-rewrite.st",
            ""),

    MCP_PARAM_EXTRACT("mcp_param_extract", "query", "MCP 工具参数提取（system）",
            "从用户问题中提取 MCP 工具所需参数的 system 提示词。",
            "prompt/mcp-parameter-extract.st",
            ""),

    MCP_PARAM_EXTRACT_USER("mcp_param_extract_user", "query", "MCP 工具参数提取（user）",
            "MCP 参数提取的 user 消息模板。",
            "prompt/mcp-parameter-extract-user.st",
            "{tool_definition},{user_question}"),

    // ==================== memory 记忆类 ====================

    CONVERSATION_SUMMARY("conversation_summary", "memory", "对话记忆摘要压缩",
            "对长对话历史进行摘要压缩，保持关键信息。",
            "prompt/conversation-summary.st",
            "{summary_max_chars}"),

    CONVERSATION_TITLE("conversation_title", "memory", "会话标题生成",
            "根据首条用户问题生成会话标题。",
            "prompt/conversation-title.st",
            "{title_max_chars},{question}"),

    // ==================== graph 图谱类 ====================

    GRAPH_EXTRACTION_SYSTEM("graph_extraction_system", "graph", "图谱抽取系统提示词（实体/关系）",
            "从 chunk 中抽取命名实体与关系的系统提示词（JSON 输出契约）。",
            "prompt/graph-extraction-system.st",
            "{max_entities},{max_relations}"),

    GRAPH_QUERY_ENTITY_SYSTEM("graph_query_entity_system", "graph", "图谱查询实体识别（system）",
            "从用户问题中识别图谱检索实体的系统提示词。",
            "prompt/graph-query-entity-system.st",
            ""),

    GRAPH_QUERY_ENTITY_USER("graph_query_entity_user", "graph", "图谱查询实体识别（user）",
            "图谱查询实体识别的 user 消息模板。",
            "prompt/graph-query-entity-user.st",
            "{max_entities},{question}"),

    // ==================== ingestion 文档处理类 ====================

    ENHANCER_CONTEXT("enhancer_context", "ingestion", "文档增强-上下文整理",
            "文档增强流水线：修复格式错误的上下文整理提示词。",
            "prompt/enhancer-context.st",
            ""),

    ENHANCER_KEYWORDS("enhancer_keywords", "ingestion", "文档增强-关键词提取",
            "文档增强流水线：提取关键词/短语（JSON 数组）。",
            "prompt/enhancer-keywords.st",
            ""),

    ENHANCER_QUESTIONS("enhancer_questions", "ingestion", "文档增强-问题生成",
            "文档增强流水线：生成理解性问题（JSON 数组）。",
            "prompt/enhancer-questions.st",
            ""),

    ENHANCER_METADATA("enhancer_metadata", "ingestion", "文档增强-元数据提取",
            "文档增强流水线：提取结构化元数据（JSON 对象）。",
            "prompt/enhancer-metadata.st",
            ""),

    ENRICHER_KEYWORDS("enricher_keywords", "ingestion", "分块富化-关键词提取",
            "分块富化：从文本块提取关键词（JSON 数组）。",
            "prompt/enricher-keywords.st",
            ""),

    ENRICHER_SUMMARY("enricher_summary", "ingestion", "分块富化-摘要生成",
            "分块富化：对文本块生成摘要。",
            "prompt/enricher-summary.st",
            ""),

    ENRICHER_METADATA("enricher_metadata", "ingestion", "分块富化-元数据提取",
            "分块富化：抽取结构化信息（JSON 对象）。",
            "prompt/enricher-metadata.st",
            ""),

    DOC_IMAGE_EXTRACT("doc_image_extract", "ingestion", "图片转 Markdown（整篇提取）",
            "多模态模型把图片/PDF 页面文字转为 Markdown（整篇文档/PDF 场景）。",
            "prompt/doc-image-extract.st",
            ""),

    DOC_IMAGE_EXTRACT_DESCRIBE("doc_image_extract_describe", "ingestion", "图片转 Markdown（含图表描述）",
            "多模态模型把图片/PDF 页面文字转为 Markdown，图表/流程图追加文字描述（单页/嵌入图场景）。",
            "prompt/doc-image-extract-describe.st",
            ""),

    // ==================== tool 工具类 ====================

    PDF_FORMAT_GUARD("pdf_format_guard", "tool", "PDF 文本排版修复（预留）",
            "PDF 文本排版修复提示词，当前无调用方，预留保留。",
            "prompt/pdf-format-guard.st",
            "");

    private final String key;
    private final String category;
    private final String name;
    private final String description;
    private final String classpathPath;
    private final String variables;

    PromptKeys(String key, String category, String name, String description,
               String classpathPath, String variables) {
        this.key = key;
        this.category = category;
        this.name = name;
        this.description = description;
        this.classpathPath = classpathPath;
        this.variables = variables;
    }

    public String key() {
        return key;
    }

    public String category() {
        return category;
    }

    public String displayName() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * classpath 默认模板路径（可为 null，表示默认内容在代码中硬编码）
     */
    public String classpathPath() {
        return classpathPath;
    }

    public String variables() {
        return variables;
    }

    /**
     * 按语义化 key 查找注册项
     */
    public static Optional<PromptKeys> fromKey(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /**
     * 按 classpath 模板路径查找注册项（供 {@link com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateLoader} 映射使用）
     */
    public static Optional<PromptKeys> fromClasspathPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(k -> k.classpathPath != null && k.classpathPath.equals(path))
                .findFirst();
    }
}
