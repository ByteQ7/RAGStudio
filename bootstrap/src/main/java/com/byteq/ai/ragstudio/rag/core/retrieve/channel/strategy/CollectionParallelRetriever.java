package com.byteq.ai.ragstudio.rag.core.retrieve.channel.strategy;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrieveRequest;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrieverService;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.AbstractParallelRetriever;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Collection 并行检索器
 * 继承模板类，实现 Collection 特定的检索逻辑
 */
@Slf4j
public class CollectionParallelRetriever extends AbstractParallelRetriever<String> {

    private final RetrieverService retrieverService;

    // 初始化 Collection 并行检索器，指定检索服务和线程池
    public CollectionParallelRetriever(RetrieverService retrieverService, Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    // 在指定 Collection 中执行向量检索，失败时返回空列表
    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, String collectionName, int topK) {
        try {
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionName(collectionName)
                            .query(question)
                            .topK(topK)
                            .build()
            );
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            // 区分常见失败原因，帮助排查
            if (msg.contains("Embedding") || msg.contains("embedding")) {
                log.error(">>> 向量检索失败：Embedding 模型可能未配置或不可用，collection={}，请检查模型配置中的 EMBEDDING 类型模型。错误: {}", collectionName, msg, e);
            } else {
                log.error("在 collection {} 中向量检索失败，错误: {}", collectionName, msg, e);
            }
            return List.of();
        }
    }

    // 获取目标标识名称，用于日志和调试
    @Override
    protected String getTargetIdentifier(String collectionName) {
        return "Collection: " + collectionName;
    }

    // 获取统计名称，用于日志输出
    @Override
    protected String getStatisticsName() {
        return "全局检索";
    }
}
