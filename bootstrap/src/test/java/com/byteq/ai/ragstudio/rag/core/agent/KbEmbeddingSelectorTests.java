package com.byteq.ai.ragstudio.rag.core.agent;

import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.core.chunk.MultimodalEmbeddingService;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 知识库语义选择器测试：
 * 覆盖 topK 截断、分数接近度扩量、阈值降级三条决策路径。
 */
class KbEmbeddingSelectorTests {

    private static final int KB_COUNT = 8;

    private EmbeddingService embeddingService = Mockito.mock(EmbeddingService.class);
    private MultimodalEmbeddingService multimodalEmbeddingService = Mockito.mock(MultimodalEmbeddingService.class);
    private DefaultModelConfigService defaultModelConfigService = Mockito.mock(DefaultModelConfigService.class);
    private KnowledgeDocumentMapper knowledgeDocumentMapper = Mockito.mock(KnowledgeDocumentMapper.class);
    private SearchChannelProperties properties = new SearchChannelProperties();

    /** 构造选择器：第 i 个知识库与问题的余弦相似度为 cosines[i] */
    private KbEmbeddingSelector selectorWith(double... cosines) {
        when(defaultModelConfigService.getModelId("tool_selector")).thenReturn("test-embed-model");
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of());
        when(embeddingService.embedBatch(anyList(), anyString())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<List<Float>> vectors = new ArrayList<>();
            // 问题向量：[1,0,0,...]
            for (int i = 0; i < texts.size(); i++) {
                List<Float> v = new ArrayList<>();
                double cos = i == 0 ? 1.0 : cosines[i - 1];
                double sin = Math.sqrt(Math.max(0, 1 - cos * cos));
                v.add((float) cos);
                v.add((float) sin);
                for (int j = 2; j < 8; j++) {
                    v.add(0f);
                }
                vectors.add(v);
            }
            return vectors;
        });
        KbEmbeddingSelector selector = new KbEmbeddingSelector(
                embeddingService, multimodalEmbeddingService, defaultModelConfigService,
                knowledgeDocumentMapper, properties);
        selector.init();
        return selector;
    }

    private List<KbEmbeddingSelector.KbInfo> kbInfos() {
        List<KbEmbeddingSelector.KbInfo> infos = new ArrayList<>();
        for (int i = 0; i < KB_COUNT; i++) {
            infos.add(new KbEmbeddingSelector.KbInfo("kb-" + i, "知识库" + i, "简介" + i, "collection-" + i));
        }
        return infos;
    }

    private Set<String> selectedIds(KbEmbeddingSelector.SelectionResult result) {
        return result.selected().stream().map(KbEmbeddingSelector.SelectedKb::id).collect(Collectors.toSet());
    }

    @Test
    void defaultTopK8KeepsAllKbsAboveThreshold() {
        // 8 个库全部高于阈值（0.30），topK 默认 8 → 全部保留，不剔除任何库
        properties.setKbSelectionTopK(8);
        properties.setKbSelectionThreshold(0.30);
        properties.setKbSelectionTieBandRatio(0.10);
        double[] cosines = {0.55, 0.53, 0.51, 0.50, 0.49, 0.48, 0.47, 0.46};
        KbEmbeddingSelector selector = selectorWith(cosines);

        KbEmbeddingSelector.SelectionResult result = selector.select("测试问题", List.of(), kbInfos());

        assertTrue(result.relevant());
        assertEquals(KB_COUNT, result.selected().size());
        assertEquals(KB_COUNT, selectedIds(result).size());
    }

    @Test
    void tightTopKExpandsToCloseScoringKb() {
        // topK=3 但第 4 名与第 3 名分数接近（差距 < 10%）→ 扩量纳入第 4 名
        properties.setKbSelectionTopK(3);
        properties.setKbSelectionThreshold(0.30);
        properties.setKbSelectionTieBandRatio(0.10);
        double[] cosines = {0.70, 0.65, 0.60, 0.58, 0.40, 0.39, 0.38, 0.37};
        KbEmbeddingSelector selector = selectorWith(cosines);

        KbEmbeddingSelector.SelectionResult result = selector.select("测试问题", List.of(), kbInfos());

        // bandFloor = 0.60 * 0.9 = 0.54 → 0.58 的库被纳入
        assertEquals(4, result.selected().size());
        assertTrue(selectedIds(result).contains("kb-3"));
        assertTrue(selectedIds(result).contains("kb-2"));
    }

    @Test
    void tightTopKKeepsClearCliff() {
        // topK=3 且第 4 名与第 3 名差距明显（> 10%）→ 不扩量
        properties.setKbSelectionTopK(3);
        properties.setKbSelectionThreshold(0.30);
        properties.setKbSelectionTieBandRatio(0.10);
        double[] cosines = {0.70, 0.65, 0.60, 0.45, 0.40, 0.39, 0.38, 0.37};
        KbEmbeddingSelector selector = selectorWith(cosines);

        KbEmbeddingSelector.SelectionResult result = selector.select("测试问题", List.of(), kbInfos());

        // bandFloor = 0.54 → 0.45 < 0.54 不纳入
        assertEquals(3, result.selected().size());
        assertTrue(!selectedIds(result).contains("kb-3"));
    }

    @Test
    void bandDisabledWhenRatioZero() {
        properties.setKbSelectionTopK(3);
        properties.setKbSelectionThreshold(0.30);
        properties.setKbSelectionTieBandRatio(0.0);
        double[] cosines = {0.70, 0.65, 0.60, 0.58, 0.40, 0.39, 0.38, 0.37};
        KbEmbeddingSelector selector = selectorWith(cosines);

        KbEmbeddingSelector.SelectionResult result = selector.select("测试问题", List.of(), kbInfos());

        assertEquals(3, result.selected().size());
    }

    @Test
    void allBelowThresholdFallsBackToBestEffort() {
        properties.setKbSelectionTopK(8);
        properties.setKbSelectionThreshold(0.30);
        properties.setKbSelectionTieBandRatio(0.10);
        // 全部低于阈值但最高分 ≥ 0.30*0.6 → 降级保留最高分 1 个
        double[] cosines = {0.20, 0.19, 0.18, 0.17, 0.16, 0.15, 0.14, 0.13};
        KbEmbeddingSelector selector = selectorWith(cosines);

        KbEmbeddingSelector.SelectionResult result = selector.select("测试问题", List.of(), kbInfos());

        assertTrue(result.relevant());
        assertEquals(1, result.selected().size());
        assertTrue(selectedIds(result).contains("kb-0"));
    }
}
