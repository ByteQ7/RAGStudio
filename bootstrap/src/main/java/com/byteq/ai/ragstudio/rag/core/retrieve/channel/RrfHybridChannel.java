package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.RrfMerger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 混合检索通道（向量 + 关键词 + RRF 融合）
 * <p>
 * 同时调起向量检索通道和关键词检索通道，然后将两者的结果通过
 * RRF（Reciprocal Rank Fusion）算法融合排序。
 * </p>
 * <p>
 * 启用时具有最高优先级，会替代单独的向量/关键词通道。
 * </p>
 */
@Slf4j
@Component
public class RrfHybridChannel implements SearchChannel {

    private final KnowledgeBaseSelectionChannel vectorChannel;
    private final KeywordSearchChannel keywordChannel;
    private final SearchChannelProperties properties;
    private final Executor executor;

    /** RRF 平滑常数 */
    private int rrfK = 60;

    /** 最终返回的 topK 结果数 */
    private int finalTopK = 5;

    public RrfHybridChannel(KnowledgeBaseSelectionChannel vectorChannel,
                            KeywordSearchChannel keywordChannel,
                            SearchChannelProperties properties,
                            Executor innerRetrievalExecutor) {
        this.vectorChannel = vectorChannel;
        this.keywordChannel = keywordChannel;
        this.properties = properties;
        this.executor = innerRetrievalExecutor;
    }

    /**
     * 设置 RRF 参数
     */
    public void configure(int rrfK, int finalTopK) {
        this.rrfK = rrfK > 0 ? rrfK : 60;
        this.finalTopK = finalTopK > 0 ? finalTopK : 5;
    }

    @Override
    public String getName() {
        return "RRFHybrid";
    }

    @Override
    public int getPriority() {
        return 0; // 最高优先级，优先走混合检索
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.getChannels().getHybridRrf().isEnabled()) return false;
        return vectorChannel.isEnabled(context) && keywordChannel.isEnabled(context);
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        log.info("执行混合检索（向量 + 关键词 + RRF）");

        try {
            // 并行调用向量通道和关键词通道（各自容错，互不影响）
            CompletableFuture<SearchChannelResult> vectorFuture =
                    CompletableFuture.supplyAsync(() -> vectorChannel.search(context), executor)
                            .exceptionally(ex -> {
                                log.error("向量检索通道异常", ex);
                                return SearchChannelResult.builder()
                                        .channelType(SearchChannelType.KNOWLEDGE_BASE_SELECTION)
                                        .channelName(vectorChannel.getName())
                                        .chunks(List.of())
                                        .latencyMs(0)
                                        .build();
                            });
            CompletableFuture<SearchChannelResult> keywordFuture =
                    CompletableFuture.supplyAsync(() -> keywordChannel.search(context), executor)
                            .exceptionally(ex -> {
                                log.error("关键词检索通道异常", ex);
                                return SearchChannelResult.builder()
                                        .channelType(SearchChannelType.KEYWORD_ES)
                                        .channelName(keywordChannel.getName())
                                        .chunks(List.of())
                                        .latencyMs(0)
                                        .build();
                            });

            SearchChannelResult vectorResult = vectorFuture.join();
            SearchChannelResult keywordResult = keywordFuture.join();

            // 收集各通道结果
            List<List<RetrievedChunk>> allResults = new ArrayList<>();
            allResults.add(vectorResult.getChunks());
            allResults.add(keywordResult.getChunks());

            // 向量通道空结果告警（帮助排查 embedding 配置问题）
            if (vectorResult.getChunks().isEmpty() && !keywordResult.getChunks().isEmpty()) {
                log.warn(">>> 向量检索无结果但关键词检索有 {} 条结果，可能原因：① Embedding 模型未配置或不可用；② 向量维度与存储不匹配；③ pgvector 扩展/HNSW 索引异常",
                        keywordResult.getChunks().size());
            }

            // RRF 融合
            List<RetrievedChunk> fused = RrfMerger.merge(allResults, finalTopK, rrfK);

            long latency = System.currentTimeMillis() - startTime;
            log.info("混合检索完成，向量通道 {} 条，关键词通道 {} 条，融合后 {} 条，耗时 {}ms",
                    vectorResult.getChunks().size(),
                    keywordResult.getChunks().size(),
                    fused.size(),
                    latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.HYBRID)
                    .channelName(getName())
                    .chunks(fused)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            // 正常情况下每个 Future 已通过 exceptionally 容错，此处仅处理 RRF 合并阶段的异常
            // 恢复中断标志，避免吞掉线程中断信号
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("RRF 融合阶段异常，返回空结果", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.HYBRID)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.HYBRID;
    }
}
