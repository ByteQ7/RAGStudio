package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.EntityIdQueryDetector;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrieverService;
import com.byteq.ai.ragstudio.rag.core.retrieve.RrfMerger;
import com.byteq.ai.ragstudio.rag.core.retrieve.ScoreClusterTopK;
import com.byteq.ai.ragstudio.rag.core.retrieve.audit.SearchAudit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final GraphRetrievalChannel graphChannel;
    private final SearchChannelProperties properties;
    private final RetrieverService retrieverService;
    private final Executor executor;

    private int rrfK = 60;

    /** 子问题参与检索的最大数量（超出丢弃，控制检索开销） */
    private static final int MAX_SUB_QUERIES = 3;

    /**
     * per-KB 内部融合超时（毫秒）：略低于外层 MultiChannelRetrievalEngine 的 30s 通道超时。
     * 超时后取消内部 future 并返回已融合结果，避免单库挂起拖垮整轮检索。
     */
    private static final long PER_KB_TIMEOUT_MS = 25_000;

    public RrfHybridChannel(KnowledgeBaseSelectionChannel vectorChannel,
                            KeywordSearchChannel keywordChannel,
                            GraphRetrievalChannel graphChannel,
                            SearchChannelProperties properties,
                            RetrieverService retrieverService,
                            Executor innerRetrievalExecutor) {
        this.vectorChannel = vectorChannel;
        this.keywordChannel = keywordChannel;
        this.graphChannel = graphChannel;
        this.properties = properties;
        this.retrieverService = retrieverService;
        this.executor = innerRetrievalExecutor;
    }

    /**
     * 配置 RRF 平滑常数（仅保留 per-KB 融合；最终数量与顺序由 Rerank + 动态 TopK 决定）
     */
    public void configure(int rrfK) {
        this.rrfK = rrfK > 0 ? rrfK : 60;
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

        // 含强实体 ID 的查询（税号/单号/编码等）：随机串向量检索既慢又无区分度，
        // 该查询跳过向量侧、只走关键词（BM25）精确匹配。
        // 按 token 提取判定（containsStrongEntityId），兼容 LLM 扩展出的
        // "ID + 中文描述"混合查询与用户输入的标点后缀形态。
        Map<String, Boolean> entityIdByQuery = new java.util.LinkedHashMap<>();
        for (String query : queries) {
            boolean entityId = EntityIdQueryDetector.containsStrongEntityId(query);
            entityIdByQuery.put(query, entityId);
            if (entityId) {
                log.info("实体ID查询跳过向量侧: query='{}', tokens={}",
                        query, EntityIdQueryDetector.extractStrongIdTokens(query));
            }
        }
        List<String> semanticQueries = queries.stream()
                .filter(q -> !Boolean.TRUE.equals(entityIdByQuery.get(q)))
                .toList();

        int perKbLimit = properties.getPerKbChunkLimit();
        if (perKbLimit <= 0) perKbLimit = 5;
        int perKbOverflowCap = properties.getPerKbOverflowCap();
        if (perKbOverflowCap < perKbLimit) perKbOverflowCap = perKbLimit;

        // 每个知识库的每个查询（主问题+子问题）的向量 + 关键词检索一次性全量并行提交
        // 向量侧跨 collection 批量嵌入：按 (模型, 维度) 分组一次调用，避免 N 个知识库 × 同一查询产生 N 次远程调用
        List<CompletableFuture<PerKbResult>> futures = new ArrayList<>();
        // 全部内部 future：任一 per-KB 超时/中断时统一取消，释放内层线程与远程连接
        List<CompletableFuture<?>> innerFutures = new ArrayList<>();
        // 纯实体 ID 查询不参与批量嵌入（节省一次远程 Embedding 调用）
        Map<String, Map<String, float[]>> preEmbeddedByCollection =
                semanticQueries.isEmpty()
                        ? Map.of()
                        : retrieverService.embedQueriesBatchPerCollection(semanticQueries, collections);
        if (CollUtil.isNotEmpty(preEmbeddedByCollection)) {
            log.info("混合检索跨库批量嵌入: collections={}, queries={}", collections.size(), semanticQueries.size());
        }
        for (String collection : collections) {
            List<CompletableFuture<SearchChannelResult>> queryFutures = new ArrayList<>();
            Map<String, float[]> preEmbedded = preEmbeddedByCollection.get(collection);
            for (String query : queries) {
                SearchContext singleCtx = buildSingleCollectionContext(context, collection, query);
                boolean entityId = Boolean.TRUE.equals(entityIdByQuery.get(query));
                if (!entityId && preEmbedded != null) {
                    singleCtx.setPreEmbeddedVector(preEmbedded.get(query));
                }
                // 纯实体 ID：向量侧跳过（占位空结果，保持 [向量, 关键词] 结果顺序），仅关键词检索
                queryFutures.add(entityId
                        ? CompletableFuture.completedFuture(emptyChannelResult(vectorChannel))
                        : CompletableFuture.supplyAsync(
                                () -> safeSearch(vectorChannel, singleCtx), executor));
                queryFutures.add(CompletableFuture.supplyAsync(
                        () -> safeSearch(keywordChannel, singleCtx), executor));
                // 图谱通道（实体锚定 + K 跳子图）：独立判断启用，未启用不产生额外开销
                if (graphChannel.isEnabled(singleCtx)) {
                    queryFutures.add(CompletableFuture.supplyAsync(
                            () -> safeSearch(graphChannel, singleCtx), executor));
                }
            }
            innerFutures.addAll(queryFutures);

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
                // 有界等待：join() 不可中断且无超时，改用 get() 保证单库融合不会无限阻塞检索线程
                PerKbResult perKb = future.get(PER_KB_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                // 按通道类型统计各通道召回量（顺序不固定：图谱通道按需加入）
                for (SearchChannelResult channelResult : perKb.results) {
                    if (channelResult.getChannelType() == SearchChannelType.KNOWLEDGE_BASE_SELECTION) {
                        vectorTotal += channelResult.getChunks().size();
                    } else if (channelResult.getChannelType() == SearchChannelType.KEYWORD_ES) {
                        keywordTotal += channelResult.getChunks().size();
                    }
                }

                // 先融合到扩量上限（让分数簇截断能看到完整分布），再做分数簇感知截断：
                // 分数接近 → 保留更多（避免正确 chunk 在粗召阶段被硬截丢失）
                // 有明显落差 → 保留更少（弱相关库不硬凑 perKbLimit 条）
                List<List<RetrievedChunk>> perKbResults = perKb.results.stream()
                        .map(SearchChannelResult::getChunks)
                        .toList();

                List<RetrievedChunk> merged = RrfMerger.merge(perKbResults, perKbOverflowCap, rrfK);
                recordRrfCandidates(context, merged, perKb.collection);
                List<RetrievedChunk> kbChunks = applyPerKbClusterTruncation(merged, perKbLimit, perKbOverflowCap);
                allChunks.addAll(kbChunks);

                log.debug("KB [{}] RRF: 向量{}条 + 关键词{}条 → 融合{}条, 簇感知截断后{}条",
                        perKb.collection, vectorTotal,
                        keywordTotal, merged.size(), kbChunks.size());
            } catch (InterruptedException ie) {
                // 外层通道超时取消了本通道任务：取消内部 future 并恢复中断标记
                Thread.currentThread().interrupt();
                log.warn("KB RRF 融合被中断，取消全部内部检索任务");
                innerFutures.forEach(f -> f.cancel(true));
                break;
            } catch (TimeoutException te) {
                log.warn("KB RRF 融合超时（{}ms），取消内部检索任务: {}", PER_KB_TIMEOUT_MS, te.getMessage());
                innerFutures.forEach(f -> f.cancel(true));
            } catch (Exception e) {
                log.warn("KB RRF 融合异常: {}", e.getMessage());
            }
        }

        // 不做跨 KB 全局截断：各 KB 的 RRF 分数不可比（查询数/召回量不同），
        // 直接按 KB 顺序 subList 会整库吞掉靠前 KB 的全部名额（如财务库独占 top-5、HR 库被整体砍掉）。
        // 全部 per-KB 融合结果进入后置处理器链，由 Rerank（cross-encoder 分数全局可比）统一排序，
        // 最终数量由动态 TopK（max-final-chunks）控制。

        long latency = System.currentTimeMillis() - startTime;
        log.info("混合检索完成: {} 个KB, 向量共{}条, 关键词共{}条, 簇感知截断后共{}条（全部进入 Rerank 阶段）, 耗时{}ms",
                collections.size(), vectorTotal, keywordTotal, allChunks.size(), latency);

        return SearchChannelResult.builder()
                .channelType(SearchChannelType.HYBRID)
                .channelName(getName())
                .chunks(allChunks)
                .latencyMs(latency)
                .build();
    }

    /**
     * 记录 RRF 融合候选到审计缓冲（开启审计日志时生效）。
     * 在簇截断之前采集，保证被截断丢弃的候选也可追溯。
     *
     * @param context   检索上下文（携带可选的 SearchAudit）
     * @param merged    RRF 融合后的完整候选（per-KB）
     * @param collection 所属向量集合名称
     */
    private void recordRrfCandidates(SearchContext context, List<RetrievedChunk> merged, String collection) {
        SearchAudit audit = context.getSearchAudit();
        if (audit == null) {
            return;
        }
        for (int i = 0; i < merged.size(); i++) {
            RetrievedChunk chunk = merged.get(i);
            if (chunk.getId() == null) {
                continue;
            }
            float score = chunk.getScore() != null ? chunk.getScore() : 0f;
            audit.addRrfCandidate(chunk.getId(), collection, score, i + 1);
        }
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

    /** 通道占位空结果（纯实体 ID 查询跳过向量检索时，保持 [向量, 关键词] 结果顺序） */
    private static SearchChannelResult emptyChannelResult(SearchChannel channel) {
        return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .chunks(List.of())
                .latencyMs(0)
                .build();
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.HYBRID;
    }
}
