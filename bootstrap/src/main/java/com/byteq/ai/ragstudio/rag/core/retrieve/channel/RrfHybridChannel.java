package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrieverService;
import com.byteq.ai.ragstudio.rag.core.retrieve.RrfMerger;
import com.byteq.ai.ragstudio.rag.core.retrieve.ScoreClusterTopK;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final RetrieverService retrieverService;
    private final Executor executor;

    private int rrfK = 60;
    private int finalTopK = 5;

    /** 子问题参与检索的最大数量（超出丢弃，控制检索开销） */
    private static final int MAX_SUB_QUERIES = 3;

    public RrfHybridChannel(KnowledgeBaseSelectionChannel vectorChannel,
                            KeywordSearchChannel keywordChannel,
                            SearchChannelProperties properties,
                            RetrieverService retrieverService,
                            Executor innerRetrievalExecutor) {
        this.vectorChannel = vectorChannel;
        this.keywordChannel = keywordChannel;
        this.properties = properties;
        this.retrieverService = retrieverService;
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
    @RagTraceNode(name = "rrf-混合检索", type = "HYBRID_RETRIEVE")
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        List<String> collections = context.getSelectedCollectionNames();
        log.info("执行混合检索（per-KB RRF），目标集合数: {}", collections.size());

        // 参与检索的查询列表：主问题 + 多问句拆分出的子问题（去重）。
        // 此前子问题只进入 LLM Prompt 从未参与检索，多问句查询按主问题单路召回，
        // 现让每个子问题都执行向量+关键词检索后统一 RRF 融合，提升多问句召回覆盖。
        List<String> queries = buildQueries(context);
        log.info("混合检索查询列表（主问题+子问题）: {}", queries);

        int perKbLimit = properties.getPerKbChunkLimit();
        if (perKbLimit <= 0) perKbLimit = 5;
        int perKbOverflowCap = properties.getPerKbOverflowCap();
        if (perKbOverflowCap < perKbLimit) perKbOverflowCap = perKbLimit;

        // 每个知识库的每个查询（主问题+子问题）的向量 + 关键词检索一次性全量并行提交
        // 向量侧跨 collection 批量嵌入：按 (模型, 维度) 分组一次调用，避免 N 个知识库 × 同一查询产生 N 次远程调用
        List<CompletableFuture<PerKbResult>> futures = new ArrayList<>();
        Map<String, Map<String, float[]>> preEmbeddedByCollection =
                retrieverService.embedQueriesBatchPerCollection(queries, collections);
        if (CollUtil.isNotEmpty(preEmbeddedByCollection)) {
            log.info("混合检索跨库批量嵌入: collections={}, queries={}", collections.size(), queries.size());
        }
        for (String collection : collections) {
            List<CompletableFuture<SearchChannelResult>> queryFutures = new ArrayList<>();
            Map<String, float[]> preEmbedded = preEmbeddedByCollection.get(collection);
            for (String query : queries) {
                SearchContext singleCtx = buildSingleCollectionContext(context, collection, query);
                if (preEmbedded != null) {
                    singleCtx.setPreEmbeddedVector(preEmbedded.get(query));
                }
                queryFutures.add(CompletableFuture.supplyAsync(
                        () -> safeSearch(vectorChannel, singleCtx), executor));
                queryFutures.add(CompletableFuture.supplyAsync(
                        () -> safeSearch(keywordChannel, singleCtx), executor));
            }

            CompletableFuture<PerKbResult> perKbFuture = CompletableFuture
                    .allOf(queryFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> new PerKbResult(collection,
                            queryFutures.stream().map(CompletableFuture::join).toList()));
            futures.add(perKbFuture);
        }

        List<RetrievedChunk> allChunks = new ArrayList<>();
        int vectorTotal = 0;
        int keywordTotal = 0;

        for (CompletableFuture<PerKbResult> future : futures) {
            try {
                PerKbResult perKb = future.join();

                // results 顺序为 [向量0, 关键词0, 向量1, 关键词1, ...]
                for (int i = 0; i < perKb.results.size(); i++) {
                    if (i % 2 == 0) {
                        vectorTotal += perKb.results.get(i).getChunks().size();
                    } else {
                        keywordTotal += perKb.results.get(i).getChunks().size();
                    }
                }

                // 先融合到扩量上限（让分数簇截断能看到完整分布），再做分数簇感知截断：
                // 分数接近 → 保留更多（避免正确 chunk 在粗召阶段被硬截丢失）
                // 有明显落差 → 保留更少（弱相关库不硬凑 perKbLimit 条）
                List<List<RetrievedChunk>> perKbResults = perKb.results.stream()
                        .map(SearchChannelResult::getChunks)
                        .toList();

                List<RetrievedChunk> merged = RrfMerger.merge(perKbResults, perKbOverflowCap, rrfK);
                List<RetrievedChunk> kbChunks = applyPerKbClusterTruncation(merged, perKbLimit, perKbOverflowCap);
                allChunks.addAll(kbChunks);

                log.debug("KB [{}] RRF: 向量{}条 + 关键词{}条 → 融合{}条, 簇感知截断后{}条",
                        perKb.collection, vectorTotal,
                        keywordTotal, merged.size(), kbChunks.size());
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
     * 构建参与检索的查询列表：主问题优先，随后追加多问句拆分的子问题（去重、限量）。
     */
    private List<String> buildQueries(SearchContext context) {
        List<String> queries = new ArrayList<>();
        String main = context.getMainQuestion();
        if (StrUtil.isNotBlank(main)) {
            queries.add(main);
        }
        if (context.getSubQuestions() != null) {
            int added = 0;
            for (String sub : context.getSubQuestions()) {
                if (added >= MAX_SUB_QUERIES) {
                    break;
                }
                if (StrUtil.isNotBlank(sub) && !queries.contains(sub)) {
                    queries.add(sub);
                    added++;
                }
            }
        }
        return queries;
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

    /** 单个知识库的多个查询（主问题+子问题）的检索结果 */
    private record PerKbResult(String collection, List<SearchChannelResult> results) {}

    private SearchContext buildSingleCollectionContext(SearchContext original, String collection, String query) {
        return SearchContext.builder()
                .originalQuestion(query)
                .rewrittenQuestion(query)
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
