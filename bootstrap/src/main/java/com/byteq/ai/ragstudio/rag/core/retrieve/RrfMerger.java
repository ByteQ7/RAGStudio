package com.byteq.ai.ragstudio.rag.core.retrieve;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF（Reciprocal Rank Fusion）融合器
 * <p>
 * 将多个检索通道的结果通过倒数排序融合算法合并。
 * 标准 RRF 公式：score = Σ 1 / (k + rank(i))
 * 其中 rank(i) 是文档在某个通道中的排名（从 1 开始），k 是平滑常数（默认 60）。
 * </p>
 */
@Slf4j
public final class RrfMerger {

    private RrfMerger() {}

    /**
     * 融合多个通道的检索结果
     *
     * @param channelResults 各通道的检索结果列表
     * @param topK           最终返回的结果数
     * @param k              RRF 平滑常数（推荐 60，取值范围 1-1000）
     * @return 按 RRF 分数降序排列的 Chunk 列表
     */
    public static List<RetrievedChunk> merge(List<List<RetrievedChunk>> channelResults, int topK, int k) {
        if (channelResults == null || channelResults.isEmpty()) {
            return List.of();
        }

        // chunk id -> RRF score
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        // chunk id -> chunk data（保留第一个出现的副本）
        Map<String, RetrievedChunk> chunkMap = new LinkedHashMap<>();

        for (List<RetrievedChunk> results : channelResults) {
            if (results == null || results.isEmpty()) continue;

            for (int i = 0; i < results.size(); i++) {
                RetrievedChunk chunk = results.get(i);
                String id = chunk.getId();
                if (id == null) continue;

                int rank = i + 1; // 排名从 1 开始
                double contribution = 1.0 / (k + rank);

                rrfScores.merge(id, contribution, Double::sum);

                // 保留第一次出现的 chunk 数据（score 会被覆盖）
                if (!chunkMap.containsKey(id)) {
                    chunkMap.put(id, chunk);
                }
            }
        }

        // 按 RRF 分数降序排列，取 topK
        List<String> sortedIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();

        // 构建最终结果
        List<RetrievedChunk> fused = new ArrayList<>();
        for (String id : sortedIds) {
            RetrievedChunk original = chunkMap.get(id);
            double rrfScore = rrfScores.get(id);
            // 保留 RRF 分数
            fused.add(RetrievedChunk.builder()
                    .id(original.getId())
                    .text(original.getText())
                    .score((float) rrfScore)
                    .build());
        }

        log.info("RRF 融合完成：输入 {} 通道共 {} 条 → 输出 {} 条",
                channelResults.size(),
                channelResults.stream().mapToInt(List::size).sum(),
                fused.size());

        return fused;
    }

    /**
     * 使用默认 k=60 的简化调用
     */
    public static List<RetrievedChunk> merge(List<List<RetrievedChunk>> channelResults, int topK) {
        return merge(channelResults, topK, 60);
    }
}
