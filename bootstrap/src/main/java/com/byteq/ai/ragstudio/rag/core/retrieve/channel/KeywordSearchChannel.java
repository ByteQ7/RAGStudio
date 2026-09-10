package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrieveRequest;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrieverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 关键词检索通道
 * <p>
 * 使用 PostgreSQL tsvector 对知识库内容进行关键词级全文检索。
 * 与 {@link KnowledgeBaseSelectionChannel} 配合使用，形成"向量 + 关键词"混合检索。
 * </p>
 */
@Slf4j
@Component
public class KeywordSearchChannel implements SearchChannel {

    /** 单个 collection 检索超时（与 RrfHybridChannel.PER_KB_TIMEOUT_MS 一致），防止 DB 挂起无限占用对话名额 */
    private static final long PER_COLLECTION_TIMEOUT_MS = 25_000;

    private final RetrieverService retrieverService;
    private final SearchChannelProperties properties;
    private final Executor executor;

    public KeywordSearchChannel(RetrieverService retrieverService,
                                SearchChannelProperties properties,
                                Executor innerRetrievalExecutor) {
        this.retrieverService = retrieverService;
        this.properties = properties;
        this.executor = innerRetrievalExecutor;
    }

    @Override
    public String getName() {
        return "KeywordSearch";
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (CollUtil.isEmpty(context.getSelectedCollectionNames())) return false;
        return properties.getChannels().getKeyword().isEnabled();
    }

    @Override
    @RagTraceNode(name = "关键词检索", type = "KEYWORD_RETRIEVE")
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            List<String> collections = context.getSelectedCollectionNames();
            String query = context.getMainQuestion();
            log.info("执行关键词检索，目标集合：{}，查询：{}", collections, query);

            int topKMultiplier = properties.getChannels().getKeyword().getTopKMultiplier();
            if (topKMultiplier <= 0) topKMultiplier = 2;
            int adjustedTopK = context.getTopK() * topKMultiplier;

            // 并行检索多个 collection
            List<CompletableFuture<List<RetrievedChunk>>> futures = new ArrayList<>();
            for (String collection : collections) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        RetrieveRequest req = RetrieveRequest.builder()
                                .collectionName(collection)
                                .query(query)
                                .topK(adjustedTopK)
                                .build();
                        return retrieverService.retrieveByKeyword(query, req);
                    } catch (Exception e) {
                        log.warn("关键词检索失败，collection={}", collection, e);
                        return List.<RetrievedChunk>of();
                    }
                }, executor));
            }

            List<RetrievedChunk> allChunks = new ArrayList<>();
            // 与 RrfHybridChannel 的 25s 兜底一致：DB 挂起（锁等待/连接池耗尽）时不再无限阻塞占用对话并发名额
            for (CompletableFuture<List<RetrievedChunk>> future : futures) {
                try {
                    allChunks.addAll(future.get(PER_COLLECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    log.warn("关键词检索单集合超时({}ms)，跳过该集合结果", PER_COLLECTION_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("关键词检索被中断", e);
                } catch (ExecutionException e) {
                    log.warn("关键词检索单集合异常: {}", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                }
            }

            long latency = System.currentTimeMillis() - startTime;
            log.info("关键词检索完成，检索到 {} 个 Chunk，耗时 {}ms", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_ES)
                    .channelName(getName())
                    .chunks(allChunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("关键词检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_ES)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD_ES;
    }
}
