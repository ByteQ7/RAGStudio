package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

/**
 * 检索通道类型枚举
 */
public enum SearchChannelType {

    /**
     * 向量全局检索
     * 在所有知识库中进行向量检索
     */
    VECTOR_GLOBAL,

    /**
     * 知识库选择检索
     * 基于用户选定的知识库，在对应向量集合中检索
     */
    KNOWLEDGE_BASE_SELECTION,

    /**
     * ES 关键词检索
     */
    KEYWORD_ES,

    /**
     * 混合检索
     */
    HYBRID
}
