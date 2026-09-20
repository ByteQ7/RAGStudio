package com.byteq.ai.ragstudio.rag.core.harness.context;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;

import java.util.List;

/**
 * Agent 循环上下文（Harness · 上下文子模块）
 * <p>
 * 承载一次 ReACT Agent 调用所需的全部输入和运行时状态：
 * <ul>
 *   <li><b>不可变输入</b>：用户问题、对话历史、KB 上下文、配置参数、动态上下文块</li>
 *   <li><b>运行时累积</b>：Agent 步骤列表</li>
 * </ul>
 * 通过 {@link Builder} 构造，避免多重重载构造器参数顺序错误。
 */
public class AgentContext {

    // ==================== 不可变输入 ====================

    /** 用户原始问题 */
    private final String question;

    /** 对话历史（含摘要，由 Memory Module 产出） */
    private final List<ChatMessage> history;

    /** 预检索的 KB 上下文文本（相关性判断为"相关"时非空，否则为空字符串） */
    private final String kbContext;

    /** 知识库相关性判断结果（true=已检索且有关，false=判断为无关或未选知识库） */
    private final boolean kbRelevant;

    /** 最大迭代次数（默认 10） */
    private final int maxIterations;

    /** 总超时时间（ms，默认 120_000） */
    private final long timeoutMs;

    /** 图片 S3 URL 列表（用于多模态识别） */
    private final List<String> imageUrls;

    /** 深度思考级别（0=关闭，1-100=开启） */
    private final int thinkingLevel;

    /** 会话 ID */
    private final String conversationId;

    /** 用户 ID */
    private final String userId;

    /** 当前请求选择的知识库 ID 列表（空列表表示无知识库可用） */
    private final List<String> knowledgeBaseIds;

    /** 知识库概要文本（名称 + collection + 描述，注入 rag_search 工具描述） */
    private final String kbSummaryText;

    /** 系统查询改写结果（上下文补全 + 指代消解，供 rag_search 弱追问替换） */
    private final String rewrittenQuery;

    /** 查询改写阶段拆分的子问题列表（供 rag_search 多问句并行召回，避免重复支付改写成本） */
    private final List<String> subQuestions;

    /** 动态上下文注册表（每请求实例；工作流候选等由 Harness 中间件注入 system message） */
    private final DynamicContextRegistry dynamicContexts;

    private AgentContext(Builder b) {
        this.question = b.question;
        this.history = b.history != null ? List.copyOf(b.history) : List.of();
        this.kbContext = b.kbContext != null ? b.kbContext : "";
        this.kbRelevant = b.kbRelevant;
        this.maxIterations = b.maxIterations > 0 ? b.maxIterations : 10;
        this.timeoutMs = b.timeoutMs > 0 ? b.timeoutMs : 120_000L;
        this.imageUrls = b.imageUrls != null ? List.copyOf(b.imageUrls) : List.of();
        this.thinkingLevel = Math.max(0, Math.min(100, b.thinkingLevel));
        this.conversationId = b.conversationId;
        this.userId = b.userId;
        this.knowledgeBaseIds = b.knowledgeBaseIds != null ? List.copyOf(b.knowledgeBaseIds) : List.of();
        this.kbSummaryText = b.kbSummaryText;
        this.rewrittenQuery = b.rewrittenQuery;
        this.subQuestions = b.subQuestions != null ? List.copyOf(b.subQuestions) : List.of();
        this.dynamicContexts = b.dynamicContexts != null ? b.dynamicContexts : new DynamicContextRegistry();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ==================== getters ====================

    public String getQuestion() { return question; }
    public List<ChatMessage> getHistory() { return history; }
    public String getKbContext() { return kbContext; }
    public boolean isKbRelevant() { return kbRelevant; }
    public int getMaxIterations() { return maxIterations; }
    public long getTimeoutMs() { return timeoutMs; }
    public List<String> getImageUrls() { return imageUrls; }
    public int getThinkingLevel() { return thinkingLevel; }
    public String getConversationId() { return conversationId; }
    public String getUserId() { return userId; }
    public List<String> getKnowledgeBaseIds() { return knowledgeBaseIds; }
    public String getKbSummaryText() { return kbSummaryText; }
    public String getRewrittenQuery() { return rewrittenQuery; }
    public List<String> getSubQuestions() { return subQuestions; }
    public DynamicContextRegistry getDynamicContexts() { return dynamicContexts; }

    @Override
    public String toString() {
        return "AgentContext{question='" + truncate(question, 50)
                + "', kbRelevant=" + kbRelevant
                + ", maxIterations=" + maxIterations + "}";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * AgentContext 构建器
     */
    public static class Builder {
        private String question;
        private List<ChatMessage> history;
        private String kbContext;
        private boolean kbRelevant;
        private int maxIterations;
        private long timeoutMs;
        private List<String> imageUrls;
        private int thinkingLevel;
        private String conversationId;
        private String userId;
        private List<String> knowledgeBaseIds;
        private String kbSummaryText;
        private String rewrittenQuery;
        private List<String> subQuestions;
        private DynamicContextRegistry dynamicContexts;

        public Builder question(String question) { this.question = question; return this; }
        public Builder history(List<ChatMessage> history) { this.history = history; return this; }
        public Builder kbContext(String kbContext) { this.kbContext = kbContext; return this; }
        public Builder kbRelevant(boolean kbRelevant) { this.kbRelevant = kbRelevant; return this; }
        public Builder maxIterations(int maxIterations) { this.maxIterations = maxIterations; return this; }
        public Builder timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Builder imageUrls(List<String> imageUrls) { this.imageUrls = imageUrls; return this; }
        public Builder thinkingLevel(int thinkingLevel) { this.thinkingLevel = thinkingLevel; return this; }
        public Builder conversationId(String conversationId) { this.conversationId = conversationId; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder knowledgeBaseIds(List<String> knowledgeBaseIds) { this.knowledgeBaseIds = knowledgeBaseIds; return this; }
        public Builder kbSummaryText(String kbSummaryText) { this.kbSummaryText = kbSummaryText; return this; }
        public Builder rewrittenQuery(String rewrittenQuery) { this.rewrittenQuery = rewrittenQuery; return this; }
        public Builder subQuestions(List<String> subQuestions) { this.subQuestions = subQuestions; return this; }
        public Builder dynamicContexts(DynamicContextRegistry dynamicContexts) { this.dynamicContexts = dynamicContexts; return this; }

        public AgentContext build() {
            return new AgentContext(this);
        }
    }
}
