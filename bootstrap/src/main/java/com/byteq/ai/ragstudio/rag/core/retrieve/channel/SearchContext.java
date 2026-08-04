package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索上下文
 * <p>
 * 携带检索所需的所有信息，在多个通道之间传递
 */
@Data
@Builder
public class SearchContext {

    /**
     * 原始问题（LLM 生成的检索查询文本，可能经过关键词扩写等改写）
     */
    private String originalQuestion;

    /**
     * 重写后的问题
     */
    private String rewrittenQuestion;

    /**
     * 用户的原始提问（未经任何改写，用于重排序阶段）
     */
    private String userOriginalQuestion;

    /**
     * 子问题列表
     */
    private List<String> subQuestions;

    /**
     * 用户选定的知识库向量集合名称列表
     */
    private List<String> selectedCollectionNames;

    /**
     * 期望返回的结果数量
     */
    private int topK;

    /**
     * 扩展元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 预嵌入查询向量（可选）
     * <p>
     * 由 RrfHybridChannel 在多查询场景下按 collection 批量嵌入后注入，
     * 向量检索通道命中该字段时直接使用（retrieveByVector），
     * 避免每个查询独立触发一次远程 embedding 调用。
     * null 表示未预嵌入，检索通道回退为自行 embed。
     * </p>
     */
    private float[] preEmbeddedVector;

    /**
     * 获取主问题（优先使用重写后的问题）
     */
    public String getMainQuestion() {
        return rewrittenQuestion != null ? rewrittenQuestion : originalQuestion;
    }
}
