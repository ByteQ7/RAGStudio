package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrieverService;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.strategy.CollectionParallelRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 知识库选择检索通道
 * <p>
 * 根据用户在前端选择的知识库 ID，从对应的向量集合中进行向量检索。
 * 用户在对话时选择了一个或多个知识库，此通道仅在这些选定知识库的 collection 中检索。
 * </p>
 */
@Slf4j
@Component
public class KnowledgeBaseSelectionChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final CollectionParallelRetriever parallelRetriever;

    public KnowledgeBaseSelectionChannel(RetrieverService retrieverService,
                                         SearchChannelProperties properties,
                                         Executor innerRetrievalExecutor) {
        this.properties = properties;
        this.parallelRetriever = new CollectionParallelRetriever(retrieverService, innerRetrievalExecutor);
    }

    @Override
    public String getName() {
        return "KnowledgeBaseSelection";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    /**
     * 当用户选择了至少一个知识库集合时启用该通道
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        return CollUtil.isNotEmpty(context.getSelectedCollectionNames());
    }

    /**
     * 对用户选定的知识库集合执行并行向量检索，topK 按配置的倍数放大后传入
     */
    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            List<String> collections = context.getSelectedCollectionNames();
            log.info("执行知识库选择检索，目标集合：{}", collections);

            int topKMultiplier = properties.getChannels().getKnowledgeBaseSelection().getTopKMultiplier();
            // 配置缺失/为 0 时兜底为 2，避免 SQL LIMIT 0 静默返回空导致检索"假性为空"
            if (topKMultiplier <= 0) topKMultiplier = 2;
            int adjustedTopK = context.getTopK() * topKMultiplier;

            List<RetrievedChunk> allChunks;
            float[] preEmbeddedVector = context.getPreEmbeddedVector();
            if (preEmbeddedVector != null && collections.size() == 1) {
                // 预嵌入向量（来自 RrfHybridChannel 批量嵌入，对应单个 collection 的查询），
                // 直接向量检索避免重复 embedding
                allChunks = parallelRetriever.executeParallelRetrieval(
                        context.getMainQuestion(),
                        collections,
                        adjustedTopK,
                        java.util.Map.of(collections.get(0), preEmbeddedVector)
                );
            } else {
                allChunks = parallelRetriever.executeParallelRetrieval(
                        context.getMainQuestion(),
                        collections,
                        adjustedTopK
                );
            }

            long latency = System.currentTimeMillis() - startTime;

            log.info("知识库选择检索完成，检索到 {} 个 Chunk，耗时 {}ms", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KNOWLEDGE_BASE_SELECTION)
                    .channelName(getName())
                    .chunks(allChunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("知识库选择检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KNOWLEDGE_BASE_SELECTION)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KNOWLEDGE_BASE_SELECTION;
    }
}
