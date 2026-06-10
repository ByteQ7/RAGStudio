package com.byteq.ai.ragstudio.rag.core.rewrite;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;

import java.util.List;

/**
 * 用户查询改写：将自然语言问题改写成适合 RAG 检索的查询语句
 */
public interface QueryRewriteService {

    /**
     * 将用户问题改写为适合向量 / 关键字检索的简洁查询
     *
     * @param userQuestion 原始用户问题
     * @return 改写后的检索查询（如果改写失败，则回退原问题）
     */
    String rewrite(String userQuestion);

    /**
     * 可选：改写 + 拆分多问句
     * 默认实现仅返回改写结果并将其作为单个子问题
     */
    default RewriteResult rewriteWithSplit(String userQuestion) {
        String rewritten = rewrite(userQuestion);
        return new RewriteResult(rewritten, List.of(rewritten));
    }

    /**
     * 可选：改写 + 拆分多问句，支持会话历史
     * 默认实现忽略历史，回退到基础改写逻辑
     */
    default RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        return rewriteWithSplit(userQuestion);
    }

    /**
     * 改写 + 拆分多问句，支持会话历史和知识库 ID 过滤
     * <p>
     * 在归一化阶段仅应用与指定知识库关联的映射规则。
     * 默认实现忽略知识库 ID，回退到无知识库过滤的改写逻辑。
     * </p>
     *
     * @param userQuestion     原始用户问题
     * @param history          会话历史消息列表
     * @param knowledgeBaseIds 用户选定的知识库 ID 列表
     * @return 改写结果，包含改写后的问题和子问题列表
     */
    default RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history, List<String> knowledgeBaseIds) {
        return rewriteWithSplit(userQuestion, history);
    }
}
