package com.byteq.ai.ragstudio.rag.core.retrieve.postprocessor;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.config.ModelConfigProvider;
import com.byteq.ai.ragstudio.infra.rerank.RerankService;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.ImageChunkResolver;
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
    private final ImageChunkResolver imageChunkResolver;

    public RerankPostProcessor(RerankService rerankService,
                               SearchChannelProperties searchProperties,
                               DefaultModelConfigService defaultModelConfigService,
                               ModelConfigProvider modelConfigProvider,
                               ImageChunkResolver imageChunkResolver) {
        this.rerankService = rerankService;
        this.searchProperties = searchProperties;
        this.defaultModelConfigService = defaultModelConfigService;
        this.modelConfigProvider = modelConfigProvider;
        this.imageChunkResolver = imageChunkResolver;
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
        String effectiveRerankModelId = userRerankModelId;
        boolean modelSupportsMultimodal = effectiveRerankModelId != null
                && isMultimodalRerankModel(effectiveRerankModelId);
        if (effectiveRerankModelId == null) {
            // 无显式 rerank 配置：自动路由将使用 rerank 组的默认模型，
            // 按其多模态能力决定图片 Chunk 是否参与重排（此前直接走文本分支，多模态模型形同虚设）
            String autoDefaultId = resolveAutoRerankDefaultId();
            modelSupportsMultimodal = autoDefaultId != null && isMultimodalRerankModel(autoDefaultId);
            effectiveRerankModelId = autoDefaultId;
        }

        String rerankQuery = resolveRerankQuery(context);

        if (modelSupportsMultimodal) {
            // 预解析图片 Chunk 的可访问地址（s3:// → base64 data URI），供 rerank 服务下载
            imageChunkResolver.enrichRerankImageUrls(chunks);
            return processWithMultimodalRerank(textChunks, imageChunks, rerankQuery, effectiveRerankModelId);
        } else {
            return processWithTextRerank(textChunks, imageChunks, rerankQuery, effectiveRerankModelId);
        }
    }

    /** 自动路由（无显式配置）时的 rerank 模型：取 rerank 组的默认模型 */
    private String resolveAutoRerankDefaultId() {
        try {
            DynamicModelConfig config = modelConfigProvider.getConfig();
            DynamicModelConfig.ModelGroup group = config.getRerankGroup();
            return group != null ? group.getDefaultModel() : null;
        } catch (Exception e) {
            log.debug("获取自动路由 rerank 默认模型失败", e);
            return null;
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
            // 远程 Rerank 前先做全局候选截断，避免多知识库粗召膨胀导致远程输入线性放大
            List<RetrievedChunk> rerankInput = limitRerankCandidates(textChunks);
            rerankedText = executeRerank(query, rerankInput, rerankInput.size(), preferredModelId);
            int finalTextCount = computeDynamicTopK(textChunks.size(), rerankedText);
            rerankedText = rerankedText.subList(0, Math.min(finalTextCount, rerankedText.size()));
            log.info("文本 Rerank 完成: 输入 {} 个, 重排后取前 {} 个", rerankInput.size(), rerankedText.size());
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

        List<RetrievedChunk> rerankInput = limitRerankCandidates(allChunks);
        List<RetrievedChunk> reranked = executeRerank(query, rerankInput, rerankInput.size(), preferredModelId);
        int finalCount = computeDynamicTopK(allChunks.size(), reranked);
        List<RetrievedChunk> result = reranked.subList(0, Math.min(finalCount, reranked.size()));

        log.info("多模态 Rerank 完成: 输入 {} 个(TEXT:{}+IMAGE:{}), 重排后取前 {} 个",
                rerankInput.size(), textChunks.size(), imageChunks.size(), result.size());
        return result;
    }

    /**
     * 远程 Rerank 前全局候选截断：按分数降序保留最多 {@code max-rerank-candidates} 条，
     * 避免多知识库场景下粗召候选膨胀（per-kb-overflow-cap × KB 数）导致远程调用输入量线性放大。
     */
    private List<RetrievedChunk> limitRerankCandidates(List<RetrievedChunk> candidates) {
        int cap = searchProperties.getMaxRerankCandidates();
        if (cap <= 0 || candidates.size() <= cap) {
            return candidates;
        }
        List<RetrievedChunk> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((RetrievedChunk c) -> c.getScore() != null ? -c.getScore() : 0));
        List<RetrievedChunk> limited = sorted.subList(0, cap);
        log.info("Rerank 候选截断: {} -> {}（max-rerank-candidates={}）", candidates.size(), cap, cap);
        return limited;
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
