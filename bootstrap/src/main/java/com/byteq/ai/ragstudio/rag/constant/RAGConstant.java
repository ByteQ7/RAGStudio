package com.byteq.ai.ragstudio.rag.constant;

/**
 * RAG 系统常量类
 *
 * <p>
 * 定义 RAG（Retrieval-Augmented Generation）系统中使用的各种常量配置，包括不限于：
 * <ul>
 *   <li>意图识别相关阈值和限制</li>
 *   <li>查询改写提示词模板</li>
 *   <li>RAG 问答提示词模板</li>
 *   <li>系统对话提示词模板</li>
 *   <li>......</li>
 * </ul>
 * </p>
 *
 * <p>
 * 这些常量主要用于控制 RAG 系统的行为和生成质量，包括意图过滤、查询优化、
 * 文档检索和智能问答等核心流程
 * </p>
 */
public class RAGConstant {

    /**
     * 多通道检索占位符键
     * <p>
     * 当没有意图识别结果时，使用此键作为 intentChunks Map 的占位符
     * 实际处理时只使用 Map 的 values，不关心具体的 key 值
     * </p>
     */
    public static final String MULTI_CHANNEL_KEY = "multi_channel";

    /**
     * 系统对话提示词模板路径
     * 定义企业知识助手「小码」的角色设定和对话规则，包括打招呼、自我介绍、问题分类处理等场景。模板通过 {@code {question}} 占位符接收用户问题。
     */
    public static final String CHAT_SYSTEM_PROMPT_PATH = "prompt/answer-chat-system.st";

    /**
     * 查询改写 + 多问句拆分提示词模板路径
     * 要求同时返回改写后的单条查询和子问题列表
     */
    public static final String QUERY_REWRITE_AND_SPLIT_PROMPT_PATH = "prompt/user-question-rewrite.st";

    /**
     * 对话记忆压缩提示词模板路径
     * 通过 {@code {summary_max_chars}} 控制摘要长度上限
     */
    public static final String CONVERSATION_SUMMARY_PROMPT_PATH = "prompt/conversation-summary.st";

    /**
     * 会话标题生成提示词模板路径
     * 通过 {@code {title_max_chars}} 与 {@code {question}} 控制标题长度与输入问题
     */
    public static final String CONVERSATION_TITLE_PROMPT_PATH = "prompt/conversation-title.st";

    /**
     * 统一 RAG 问答提示词模板路径
     * 合并了 KB-only、MCP-only、MCP+KB 三种场景，根据输入标签自动匹配规则
     */
    public static final String ANSWER_CHAT_PATH = "prompt/answer-chat.st";

    /**
     * MCP 工具参数提取提示词模板路径
     * 用于从用户问题中提取工具调用参数
     */
    public static final String MCP_PARAMETER_EXTRACT_PROMPT_PATH = "prompt/mcp-parameter-extract.st";

    /**
     * MCP 工具参数提取用户消息提示词模板路径
     * 用于构建包含工具定义和用户问题的用户消息，通过 {@code {tool_definition}} 和 {@code {user_question}} 占位符注入内容
     */
    public static final String MCP_PARAMETER_EXTRACT_USER_PROMPT_PATH = "prompt/mcp-parameter-extract-user.st";

    // ==================== S3 文件存储常量 ====================

    /**
     * 统一 S3 存储桶名称
     */
    public static final String S3_BUCKET_NAME = "ragstudio";

    /**
     * 知识库文档在 S3 中的前缀目录
     */
    public static final String S3_DOCUMENT_PREFIX = "document";

    /**
     * 对话图片在 S3 中的前缀目录
     */
    public static final String S3_CHAT_IMAGE_PREFIX = "chat-img";

    /**
     * AI 供应商图标在 S3 中的前缀目录
     */
    public static final String S3_AI_PROVIDER_ICON_PREFIX = "provider-icons";

    /**
     * 用户头像在 S3 中的前缀目录
     */
    public static final String S3_USER_AVATAR_PREFIX = "user-img";

    // ==================== 上下文格式化模板（单文件多 section） ====================

    /**
     * 上下文格式化模板文件路径
     * <p>
     * 包含所有上下文格式化所需的 section，通过 {@code --- section: name ---} 分隔，
     * 使用 {@code PromptTemplateLoader.renderSection(path, section, slots)} 渲染
     */
    public static final String CONTEXT_FORMAT_PATH = "prompt/context-format.st";
}
