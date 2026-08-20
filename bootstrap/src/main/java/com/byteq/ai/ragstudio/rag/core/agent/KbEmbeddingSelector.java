package com.byteq.ai.ragstudio.rag.core.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.core.chunk.MultimodalEmbeddingService;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库语义选择器
 * <p>
 * 用嵌入模型将用户问题与各知识库（名称 + 描述 + 文档名）映射到同一向量空间，
 * 按余弦相似度选出与问题相关的知识库：高于 {@code rag.search.kb-selection-threshold}
 * 阈值的知识库全部选中（最多 {@code rag.search.kb-selection-top-k} 个）参与检索。
 * <p>
 * 选择嵌入模型复用「语义选择嵌入模型」（默认模型配置 key：tool_selector）：
 * <ul>
 *   <li>文本：用户问题与知识库描述文本使用同一模型嵌入，保证向量空间一致</li>
 *   <li>图片（多模态）：用户带图提问时，额外用多模态嵌入能力编码图片，
 *       与知识库向量比对取最大相似度，支持"看图选库"场景</li>
 * </ul>
 * 相比旧的 LLM 判断方式，嵌入选择确定性更高、不会漏选或误选知识库。
 */
@Slf4j
@Component
public class KbEmbeddingSelector {

    /** 默认模型配置 key（与工具语义筛选共用同一个「语义选择嵌入模型」） */
    private static final String SELECTION_CONFIG_KEY = "tool_selector";

    /** 每个知识库最多纳入嵌入文本的文档名数量 */
    private static final int MAX_DOC_NAMES_PER_KB = 30;

    private final EmbeddingService embeddingService;
    private final MultimodalEmbeddingService multimodalEmbeddingService;
    private final DefaultModelConfigService defaultModelConfigService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final SearchChannelProperties searchProperties;

    /** 当前使用的选择嵌入模型（null 表示使用系统默认 Embedding 路由） */
    private volatile String selectionModel;

    public KbEmbeddingSelector(EmbeddingService embeddingService,
                               MultimodalEmbeddingService multimodalEmbeddingService,
                               DefaultModelConfigService defaultModelConfigService,
                               KnowledgeDocumentMapper knowledgeDocumentMapper,
                               SearchChannelProperties searchProperties) {
        this.embeddingService = embeddingService;
        this.multimodalEmbeddingService = multimodalEmbeddingService;
        this.defaultModelConfigService = defaultModelConfigService;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.searchProperties = searchProperties;
    }

    @PostConstruct
    public void init() {
        String stored = defaultModelConfigService.getModelId(SELECTION_CONFIG_KEY);
        if (StrUtil.isNotBlank(stored)) {
            this.selectionModel = stored;
            log.info("知识库语义选择器使用配置的嵌入模型: {}", stored);
        }
    }

    /**
     * 切换选择嵌入模型（重建后的索引在下次选择时生效，因为选择时实时嵌入，无需额外重建）
     */
    public void setModelAndRebuild(String modelId) {
        this.selectionModel = modelId;
        log.info("知识库语义选择嵌入模型已切换: {}", modelId != null ? modelId : "使用系统默认");
    }

    public String getEnabledModel() {
        return selectionModel;
    }

    /**
     * 为用户问题选择相关的知识库
     *
     * @param userQuestion   用户原始问题（文本）
     * @param imageDataUris  用户问题附带的图片 data URI 列表（可为空，用于多模态选库）
     * @param kbInfos        候选知识库信息（用户已选择的知识库）
     * @return 选择结果：高于阈值的知识库全部命中，最多 kbSelectionTopK 个
     */
    public SelectionResult select(String userQuestion, List<String> imageDataUris, List<KbInfo> kbInfos) {
        if (StrUtil.isBlank(userQuestion) || CollUtil.isEmpty(kbInfos)) {
            return SelectionResult.notRelevant("问题为空或未选择知识库");
        }

        List<KbText> kbTexts = buildKbTexts(kbInfos);
        if (kbTexts.isEmpty()) {
            return SelectionResult.notRelevant("知识库信息为空");
        }

        // 1. 一次批量调用完成 问题 + 所有知识库 的文本嵌入（保证同一向量空间）
        List<String> texts = new ArrayList<>(kbTexts.size() + 1);
        texts.add(userQuestion);
        for (KbText t : kbTexts) {
            texts.add(t.text);
        }

        List<List<Float>> vectors;
        try {
            vectors = embedBatch(texts);
        } catch (Exception e) {
            log.warn("知识库语义选择嵌入失败，降级为全量检索所有已选知识库: {}", e.getMessage());
            return SelectionResult.relevant("嵌入调用失败，默认检索全部知识库",
                    kbInfos.stream().map(info -> new SelectedKb(info.id(), info.name(), 0)).toList());
        }

        if (vectors == null || vectors.size() != texts.size()) {
            log.warn("知识库语义选择嵌入结果数量不匹配: {} / {}", vectors != null ? vectors.size() : 0, texts.size());
            return SelectionResult.relevant("嵌入结果异常，默认检索全部知识库",
                    kbInfos.stream().map(info -> new SelectedKb(info.id(), info.name(), 0)).toList());
        }

        // 2. 计算文本相似度
        float[] queryVec = toFloatArray(vectors.get(0));
        List<ScoredKb> scored = new ArrayList<>(kbTexts.size());
        for (int i = 0; i < kbTexts.size(); i++) {
            double sim = cosineSimilarity(queryVec, toFloatArray(vectors.get(i + 1)));
            scored.add(new ScoredKb(kbTexts.get(i).info, sim));
        }

        // 3. 多模态增强：带图提问时，用图片向量与知识库向量比对，取文本/图片相似度最大值
        if (CollUtil.isNotEmpty(imageDataUris) && !isZeroVector(queryVec)) {
            try {
                List<List<Float>> imageVectors = multimodalEmbeddingService.embedImages(imageDataUris, selectionModel);
                if (CollUtil.isNotEmpty(imageVectors)) {
                    for (int i = 0; i < scored.size(); i++) {
                        // 图片向量与「该知识库自身的文本向量」比对（vectors.get(i+1) 对应 kbTexts.get(i)）；
                        // 此前误用 queryVec 比对图片向量——该值对所有知识库相同，选库退化为原始顺序，图片内容无法区分库
                        float[] kbVector = toFloatArray(vectors.get(i + 1));
                        double bestImageSim = -1;
                        for (List<Float> iv : imageVectors) {
                            double s = cosineSimilarity(kbVector, toFloatArray(iv));
                            if (s > bestImageSim) bestImageSim = s;
                        }
                        if (bestImageSim > scored.get(i).score) {
                            scored.get(i).score = bestImageSim;
                        }
                    }
                    log.info("知识库语义选择完成多模态增强: images={}", imageVectors.size());
                }
            } catch (Exception e) {
                log.warn("图片嵌入失败（选择模型可能不支持多模态），仅使用文本相似度: {}", e.getMessage());
            }
        }

        // 4. 阈值筛选：高于阈值的知识库全部选中，数量受 TopK 限制
        scored.sort(Comparator.comparingDouble((ScoredKb s) -> s.score).reversed());
        double threshold = searchProperties.getKbSelectionThreshold();
        int topK = searchProperties.getKbSelectionTopK();

        List<SelectedKb> selected = new ArrayList<>();
        for (ScoredKb sk : scored) {
            if (sk.score >= threshold) {
                selected.add(new SelectedKb(sk.info.id(), sk.info.name(), sk.score));
                if (selected.size() >= topK) {
                    break;
                }
            }
        }

        // 4.5 分数接近度扩量：与最后一个入选知识库分数接近的候选一并保留。
        // 嵌入模型输出存在抖动（服务端更新、缓存向量陈旧等），第 topK 名边缘的分数差
        // 不构成可靠依据，硬截断可能把正确知识库排除在检索范围之外。
        double bandRatio = searchProperties.getKbSelectionTieBandRatio();
        if (bandRatio > 0 && !selected.isEmpty() && selected.size() < scored.size()) {
            double lastScore = selected.get(selected.size() - 1).score();
            double bandFloor = lastScore * (1 - bandRatio);
            for (ScoredKb sk : scored) {
                if (selected.size() >= scored.size()) {
                    break;
                }
                if (sk.score < bandFloor) {
                    break;
                }
                if (sk.score < threshold) {
                    break;
                }
                boolean exists = false;
                for (SelectedKb s : selected) {
                    if (s.id().equals(sk.info.id())) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    selected.add(new SelectedKb(sk.info.id(), sk.info.name(), sk.score));
                }
            }
            if (selected.size() > topK) {
                log.info("知识库语义选择分数接近度扩量: topK={}, 扩量至 {} 个", topK, selected.size());
            }
        }

        // 5. 全部低于阈值时不降级：明确判定为与知识库无关，不检索任何知识库。
        // 注意：曾有过"降级保留最佳知识库"的兜底（分数 ≥ 阈值的 60%），它会把明显无关的问题
        // （如实时天气、闲聊）误判为"相关"并强制触发知识库检索，已移除。
        if (selected.isEmpty()) {
            double maxScore = scored.isEmpty() ? 0 : scored.get(0).score;
            log.info("知识库语义选择: 无相关知识库, 最高相似度={}", String.format("%.3f", maxScore));
            return SelectionResult.notRelevant("问题与所有知识库相似度均低于阈值 "
                    + threshold + "（最高 " + String.format("%.3f", maxScore) + "）");
        }

        StringBuilder sb = new StringBuilder();
        for (SelectedKb sk : selected) {
            if (sb.length() > 0) sb.append("，");
            sb.append(sk.name()).append("=").append(String.format("%.3f", sk.score));
        }
        log.info("知识库语义选择: 选中 {} 个知识库: {}", selected.size(), sb);
        return SelectionResult.relevant("相似度: " + sb, selected);
    }

    // ==================== 内部方法 ====================

    private List<List<Float>> embedBatch(List<String> texts) {
        if (selectionModel != null) {
            return embeddingService.embedBatch(texts, selectionModel);
        }
        return embeddingService.embedBatch(texts);
    }

    /**
     * 构建知识库的嵌入文本：名称 + 描述 + collection + 文档名列表
     * <p>
     * 文档名查询按知识库逐个 LIMIT {@link #MAX_DOC_NAMES_PER_KB} 条：
     * 避免“先查全部启用文档再内存截断”在大知识库场景下的全量扫描开销。
     * 知识库数量有限（≤ kb-selection-top-k），少量小查询优于一次全量查询。
     * </p>
     */
    private List<KbText> buildKbTexts(List<KbInfo> kbInfos) {
        // 批量查询这些知识库下启用的文档名，增强选库准确性
        List<String> kbIds = kbInfos.stream().map(KbInfo::id).toList();
        Map<String, List<String>> docsByKb = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(kbIds)) {
            for (String kbId : kbIds) {
                try {
                    List<KnowledgeDocumentDO> docs = knowledgeDocumentMapper.selectList(
                            Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                                    .select(KnowledgeDocumentDO::getKbId, KnowledgeDocumentDO::getDocName)
                                    .eq(KnowledgeDocumentDO::getKbId, kbId)
                                    .eq(KnowledgeDocumentDO::getEnabled, 1)
                                    .orderByDesc(KnowledgeDocumentDO::getUpdateTime)
                                    .last("LIMIT " + MAX_DOC_NAMES_PER_KB));
                    for (KnowledgeDocumentDO doc : docs) {
                        if (StrUtil.isBlank(doc.getDocName())) continue;
                        docsByKb.computeIfAbsent(doc.getKbId(), k -> new ArrayList<>()).add(doc.getDocName());
                    }
                } catch (Exception e) {
                    log.warn("查询知识库文档名失败，仅使用名称+描述进行选择: kbId={}", kbId, e);
                }
            }
        }

        List<KbText> result = new ArrayList<>(kbInfos.size());
        for (KbInfo info : kbInfos) {
            StringBuilder sb = new StringBuilder();
            sb.append("知识库「").append(info.name()).append("」");
            if (StrUtil.isNotBlank(info.collectionName())) {
                sb.append("（collection: ").append(info.collectionName()).append("）");
            }
            if (StrUtil.isNotBlank(info.description())) {
                sb.append("，简介：").append(info.description());
            }
            List<String> docNames = docsByKb.get(info.id());
            if (CollUtil.isNotEmpty(docNames)) {
                List<String> limited = docNames.size() > MAX_DOC_NAMES_PER_KB
                        ? docNames.subList(0, MAX_DOC_NAMES_PER_KB)
                        : docNames;
                sb.append("，包含文档：").append(String.join("、", limited));
            }
            result.add(new KbText(info, sb.toString()));
        }
        return result;
    }

    private static float[] toFloatArray(List<Float> vec) {
        if (vec == null) return new float[0];
        float[] arr = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            arr[i] = vec.get(i);
        }
        return arr;
    }

    private static boolean isZeroVector(float[] a) {
        for (float v : a) {
            if (v != 0) return false;
        }
        return true;
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    // ==================== 支持类型 ====================

    /** 知识库基本信息 */
    public record KbInfo(String id, String name, String description, String collectionName) {}

    /** 知识库嵌入文本 */
    private record KbText(KbInfo info, String text) {}

    /** 带分数的知识库候选 */
    private static class ScoredKb {
        private final KbInfo info;
        private double score;

        private ScoredKb(KbInfo info, double score) {
            this.info = info;
            this.score = score;
        }
    }

    /** 选中的知识库 */
    public record SelectedKb(String id, String name, double score) {}

    /** 知识库选择结果 */
    public record SelectionResult(boolean relevant, String reasoning, List<SelectedKb> selected) {
        public static SelectionResult relevant(String reasoning, List<SelectedKb> selected) {
            return new SelectionResult(true, reasoning, selected);
        }

        public static SelectionResult notRelevant(String reasoning) {
            return new SelectionResult(false, reasoning, List.of());
        }

        public boolean hasSpecificCollections() {
            return relevant && selected != null && !selected.isEmpty();
        }
    }
}
