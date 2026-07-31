package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.RrfMerger;
import com.byteq.ai.ragstudio.rag.core.retrieve.ScoreClusterTopK;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 混合检索通道（向量 + 关键词 + RRF 融合）
 * <p>
 * 对每个知识库独立执行向量检索 + 关键词检索，然后 per-collection RRF 融合，
 * 保证每个知识库最多贡献 perKbChunkLimit 个 Chunk，避免某一知识库主导检索结果。
 * </p>
 */
@Slf4j
@Component
public class RrfHybridChannel implements SearchChannel {

    private final KnowledgeBaseSelectionChannel vectorChannel;
    private final KeywordSearchChannel keywordChannel;
    private final SearchChannelProperties properties;
    private final Executor executor;

    private int rrfK = 60;
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
        return 0;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.getChannels().getHybridRrf().isEnabled()) return false;
        return vectorChannel.isEnabled(context) && keywordChannel.isEnabled(context);
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        List<String> collections = context.getSelectedCollectionNames();
        log.info("执行混合检索（per-KB RRF），目标集合数: {}", collections.size());

        int perKbLimit = properties.getPerKbChunkLimit();
        if (perKbLimit <= 0) perKbLimit = 5;
        int perKbOverflowCap = properties.getPerKbOverflowCap();
        if (perKbOverflowCap < perKbLimit) perKbOverflowCap = perKbLimit;

        // 所有知识库的向量 + 关键词检索一次性全量并行提交，避免多知识库串行检索
        List<CompletableFuture<PerKbResult>> futures = new ArrayList<>();
        for (String collection : collections) {
            SearchContext singleCtx = buildSingleCollectionContext(context, collection);

            CompletableFuture<SearchChannelResult> vectorFuture =
                    CompletableFuture.supplyAsync(() -> safeSearch(vectorChannel, singleCtx), executor);
            CompletableFuture<SearchChannelResult> keywordFuture =
                    CompletableFuture.supplyAsync(() -> safeSearch(keywordChannel, singleCtx), executor);

            futures.add(vectorFuture.thenCombine(keywordFuture,
                    (v, k) -> new PerKbResult(collection, v, k)));
        }

        List<RetrievedChunk> allChunks = new ArrayList<>();
        int vectorTotal = 0;
        int keywordTotal = 0;

        for (CompletableFuture<PerKbResult> future : futures) {
            try {
                PerKbResult perKb = future.join();

                vectorTotal += perKb.vectorResult.getChunks().size();
                keywordTotal += perKb.keywordResult.getChunks().size();

                // 先融合到扩量上限（让分数簇截断能看到完整分布），再做分数簇感知截断：
                // 分数接近 → 保留更多（避免正确 chunk 在粗召阶段被硬截丢失）
                // 有明显落差 → 保留更少（弱相关库不硬凑 perKbLimit 条）
                List<List<RetrievedChunk>> perKbResults = new ArrayList<>();
                perKbResults.add(perKb.vectorResult.getChunks());
                perKbResults.add(perKb.keywordResult.getChunks());

                List<RetrievedChunk> merged = RrfMerger.merge(perKbResults, perKbOverflowCap, rrfK);
                List<RetrievedChunk> kbChunks = applyPerKbClusterTruncation(merged, perKbLimit, perKbOverflowCap);
                allChunks.addAll(kbChunks);

                log.debug("KB [{}] RRF: 向量{}条 + 关键词{}条 → 融合{}条, 簇感知截断后{}条",
                        perKb.collection, perKb.vectorResult.getChunks().size(),
                        perKb.keywordResult.getChunks().size(), merged.size(), kbChunks.size());
            } catch (Exception e) {
                log.warn("KB RRF 融合异常: {}", e.getMessage());
            }
        }

        long latency = System.currentTimeMillis() - startTime;
        log.info("混合检索完成: {} 个KB, 向量共{}条, 关键词共{}条, 簇感知截断后共{}条, 耗时{}ms",
                collections.size(), vectorTotal, keywordTotal, allChunks.size(), latency);

        return SearchChannelResult.builder()
                .channelType(SearchChannelType.HYBRID)
                .channelName(getName())
                .chunks(allChunks)
                .latencyMs(latency)
                .build();
    }

    /**
     * per-KB 分数簇感知截断：复用 {@link ScoreClusterTopK} 的分布决策逻辑。
     * RRF 分数为 rank 量级（~0.03），minScore 传入 0 时自动走"尺度降级"分支，不做底线过滤。
     */
    static List<RetrievedChunk> applyPerKbClusterTruncation(List<RetrievedChunk> chunks,
                                                            int baseMax, int overflowCap) {
        if (chunks == null || chunks.isEmpty() || chunks.size() <= baseMax) {
            return chunks;
        }
        List<Float> scores = chunks.stream().map(RetrievedChunk::getScore).toList();
        int count = ScoreClusterTopK.compute(
                scores, baseMax, 0, true, 0.15, 0.15, overflowCap);
        if (count >= chunks.size()) {
            return chunks;
        }
        return chunks.subList(0, Math.max(1, count));
    }

    /** 单个知识库的向量 + 关键词检索结果 */
    private record PerKbResult(String collection,
                               SearchChannelResult vectorResult,
                               SearchChannelResult keywordResult) {}

    private SearchContext buildSingleCollectionContext(SearchContext original, String collection) {
        return SearchContext.builder()
                .originalQuestion(original.getOriginalQuestion())
                .rewrittenQuestion(original.getRewrittenQuestion())
                .userOriginalQuestion(original.getUserOriginalQuestion())
                .subQuestions(original.getSubQuestions())
                .selectedCollectionNames(List.of(collection))
                .topK(original.getTopK())
                .metadata(original.getMetadata() != null ? new java.util.HashMap<>(original.getMetadata()) : new java.util.HashMap<>())
                .build();
    }

    private SearchChannelResult safeSearch(SearchChannel channel, SearchContext ctx) {
        try {
            return channel.search(ctx);
        } catch (Exception e) {
            log.error("检索通道 {} 异常", channel.getName(), e);
            return SearchChannelResult.builder()
                    .channelType(channel.getType())
                    .channelName(channel.getName())
                    .chunks(List.of())
                    .latencyMs(0)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.HYBRID;
    }
}
