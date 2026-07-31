package com.byteq.ai.ragstudio.rag.core.retrieve.postprocessor;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.config.ModelConfigProvider;
import com.byteq.ai.ragstudio.infra.rerank.RerankService;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.ScoreClusterTopK;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.SearchChannelResult;
import com.byteq.ai.ragstudio.rag.core.retrieve.channel.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rerank 后置处理器
 * <p>
 * 使用用户配置的重排序模型对跨知识库的检索结果进行全局语义重排序。
 * 支持文本型和多模态型两种重排序模型，根据模型能力和 Chunk 类型自动分流处理。
 * 通过动态 TopK 算法智能决定最终送入 LLM 的 Chunk 数量，而非硬编码截断。
 * </p>
 * <h3>处理流程</h3>
 * <ol>
 *   <li>分离 TEXT / IMAGE Chunk</li>
 *   <li>确定重排序模型（用户配置优先 → 自动路由降级）</li>
 *   <li>判断模型是否支持多模态</li>
 *   <li>文本型模型：TEXT Chunk 重排序 → 动态TopK；IMAGE Chunk 按原始分数排序后附在末尾</li>
 *   <li>多模态模型：TEXT + IMAGE 统一重排序 → 动态TopK</li>
 * </ol>
 */
@Slf4j
@Component
public class RerankPostProcessor implements SearchResultPostProcessor {

    private static final String RERANK_CONFIG_KEY = "rerank";

    private final RerankService rerankService;
    private final SearchChannelProperties searchProperties;
    private final DefaultModelConfigService defaultModelConfigService;
    private final ModelConfigProvider modelConfigProvider;

    public RerankPostProcessor(RerankService rerankService,
                               SearchChannelProperties searchProperties,
                               DefaultModelConfigService defaultModelConfigService,
                               ModelConfigProvider modelConfigProvider) {
        this.rerankService = rerankService;
        this.searchProperties = searchProperties;
        this.defaultModelConfigService = defaultModelConfigService;
        this.modelConfigProvider = modelConfigProvider;
    }

    @Override
    public String getName() {
        return "Rerank";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    @RagTraceNode(name = "重排序", type = "RERANK")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            log.info("Chunk 列表为空，跳过 Rerank");
            return chunks;
        }

        List<RetrievedChunk> imageChunks = chunks.stream()
                .filter(RetrievedChunk::isImage)
                .collect(Collectors.toList());
        List<RetrievedChunk> textChunks = chunks.stream()
                .filter(c -> !c.isImage())
                .collect(Collectors.toList());

        String userRerankModelId = resolveUserRerankModelId();
        boolean modelSupportsMultimodal = userRerankModelId != null
                && isMultimodalRerankModel(userRerankModelId);

        String rerankQuery = resolveRerankQuery(context);

        if (modelSupportsMultimodal) {
            return processWithMultimodalRerank(textChunks, imageChunks, rerankQuery, userRerankModelId);
        } else {
            return processWithTextRerank(textChunks, imageChunks, rerankQuery, userRerankModelId);
        }
    }

    private String resolveUserRerankModelId() {
        try {
            return defaultModelConfigService.getModelId(RERANK_CONFIG_KEY);
        } catch (Exception e) {
            log.debug("获取用户配置的重排序模型失败，将使用自动路由", e);
            return null;
        }
    }

    private boolean isMultimodalRerankModel(String modelId) {
        DynamicModelConfig config = modelConfigProvider.getConfig();
        return config.getModels().stream()
                .anyMatch(m -> modelId.equals(m.getId()) && Boolean.TRUE.equals(m.getSupportsMultimodal()));
    }

    private String resolveRerankQuery(SearchContext context) {
        String userQuery = context.getUserOriginalQuestion();
        if (StrUtil.isNotBlank(userQuery)) {
            return userQuery;
        }
        return context.getMainQuestion();
    }

    private List<RetrievedChunk> processWithTextRerank(List<RetrievedChunk> textChunks,
                                                        List<RetrievedChunk> imageChunks,
                                                        String query,
                                                        String preferredModelId) {
        List<RetrievedChunk> rerankedText;
        if (!textChunks.isEmpty()) {
            rerankedText = executeRerank(query, textChunks, textChunks.size(), preferredModelId);
            int finalTextCount = computeDynamicTopK(textChunks.size(), rerankedText);
            rerankedText = rerankedText.subList(0, Math.min(finalTextCount, rerankedText.size()));
            log.info("文本 Rerank 完成: 输入 {} 个, 重排后取前 {} 个", textChunks.size(), rerankedText.size());
        } else {
            rerankedText = List.of();
        }

        List<RetrievedChunk> merged = new ArrayList<>(rerankedText);

        imageChunks.sort(Comparator.comparingDouble(c -> c.getScore() != null ? -c.getScore() : 0));
        merged.addAll(imageChunks);

        log.info("Rerank 最终: 文本 {} 个 + 图片 {} 个 = 共 {} 个 Chunk",
                rerankedText.size(), imageChunks.size(), merged.size());
        return merged;
    }

    private List<RetrievedChunk> processWithMultimodalRerank(List<RetrievedChunk> textChunks,
                                                              List<RetrievedChunk> imageChunks,
                                                              String query,
                                                              String preferredModelId) {
        List<RetrievedChunk> allChunks = new ArrayList<>(textChunks);
        allChunks.addAll(imageChunks);

        if (allChunks.isEmpty()) {
            return allChunks;
        }

        List<RetrievedChunk> reranked = executeRerank(query, allChunks, allChunks.size(), preferredModelId);
        int finalCount = computeDynamicTopK(allChunks.size(), reranked);
        List<RetrievedChunk> result = reranked.subList(0, Math.min(finalCount, reranked.size()));

        log.info("多模态 Rerank 完成: 输入 {} 个(TEXT:{}+IMAGE:{}), 重排后取前 {} 个",
                allChunks.size(), textChunks.size(), imageChunks.size(), result.size());
        return result;
    }

    private List<RetrievedChunk> executeRerank(String query, List<RetrievedChunk> candidates,
                                                int topN, String preferredModelId) {
        try {
            if (StrUtil.isNotBlank(preferredModelId)) {
                return rerankService.rerankWithModel(query, candidates, topN, preferredModelId);
            }
            return rerankService.rerank(query, candidates, topN);
        } catch (Exception e) {
            log.warn("Rerank 调用失败，降级为原始排序: {}", e.getMessage());
            return candidates;
        }
    }

    private int computeDynamicTopK(int inputSize, List<RetrievedChunk> reranked) {
        List<Float> scores = reranked.stream().map(RetrievedChunk::getScore).toList();
        int result = computeDynamicTopK(scores,
                searchProperties.getMaxFinalChunks(),
                searchProperties.getRerankMinScore(),
                searchProperties.isDynamicTopKEnabled(),
                searchProperties.getDynamicTopKScoreGap(),
                searchProperties.getDynamicTopKTieBandRatio(),
                searchProperties.getDynamicTopKOverflowCap(),
                searchProperties.getDynamicTopKMinChunks(),
                searchProperties.getDynamicTopKCliffRatio());
        if (result != reranked.size()) {
            String topScores = scores.stream()
                    .filter(java.util.Objects::nonNull)
                    .limit(5)
                    .map(s -> String.format("%.3f", s))
                    .collect(Collectors.joining(", "));
            log.info("动态 TopK: 输入 {} 个, 最高分={}, 分数top5=[{}], 最终取 {} 个",
                    reranked.size(),
                    reranked.isEmpty() ? "-" : String.format("%.3f", reranked.get(0).getScore()),
                    topScores,
                    result);
        }
        return result;
    }

    /**
     * 分数簇感知的动态 TopK（委托 {@link ScoreClusterTopK}）：
     * <ul>
     *   <li>存在显著落差 → 截断在落差处（保底 minChunks 条），分数远高于后面的前几个 chunk 不再硬塞满 baseMax</li>
     *   <li>无显著落差（分数接近）→ 按接近度扩量，最多 overflowCap 个，避免截断丢弃同分数的正确 chunk</li>
     * </ul>
     *
     * @param scores       已按分数降序排列的 chunk 分数（可为 null，按"接近"保守处理）
     * @param baseMax      基准目标值（默认 5）
     * @param minScore     绝对底线，低于此分数的 chunk 直接排除
     * @param enabled      总开关，关闭时固定返回 baseMax
     * @param absGap       绝对落差阈值
     * @param tieBandRatio 相对接近度比例
     * @param overflowCap  扩量上限
     * @param minChunks    缩量保底（默认 3）
     */
    static int computeDynamicTopK(List<Float> scores, int baseMax, double minScore, boolean enabled,
                                  double absGap, double tieBandRatio, int overflowCap, int minChunks) {
        return ScoreClusterTopK.compute(scores, baseMax, minScore, enabled, absGap, tieBandRatio, overflowCap, minChunks);
    }

    /**
     * 完整 9 参版本（含悬崖比值阈值），供生产路径使用
     */
    static int computeDynamicTopK(List<Float> scores, int baseMax, double minScore, boolean enabled,
                                  double absGap, double tieBandRatio, int overflowCap, int minChunks,
                                  double cliffRatio) {
        return ScoreClusterTopK.compute(scores, baseMax, minScore, enabled, absGap, tieBandRatio, overflowCap, minChunks, cliffRatio);
    }

    /**
     * 兼容 7 参版本（minChunks=1），供既有单测使用
     */
    static int computeDynamicTopK(List<Float> scores, int baseMax, double minScore, boolean enabled,
                                  double absGap, double tieBandRatio, int overflowCap) {
        return ScoreClusterTopK.compute(scores, baseMax, minScore, enabled, absGap, tieBandRatio, overflowCap, 1);
    }
}
