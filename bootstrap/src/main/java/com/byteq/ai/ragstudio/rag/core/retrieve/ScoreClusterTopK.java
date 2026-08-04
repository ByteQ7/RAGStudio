package com.byteq.ai.ragstudio.rag.core.retrieve;

import java.util.List;
import java.util.Objects;

/**
 * 分数簇感知的动态 TopK 纯函数
 * <p>
 * 由分数分布决定保留数量，而不是固定前 N 个：
 * <ul>
 *   <li>找第一个显著落差（相邻分数 gap ≥ max(绝对阈值, 相对比例×最高分)）：
 *       截断在落差处，分数远高于后面的前几个 chunk 不再硬塞满 baseMax，节省 token</li>
 *   <li>无显著落差（分数接近）：按与最高分的相对接近度扩量，
 *       最多 overflowCap 个，避免截断丢弃同分数的正确 chunk</li>
 * </ul>
 * 应用于两个层面：
 * <ul>
 *   <li>粗召层：per-KB RRF 融合后决定每个知识库进入 rerank 的候选数（{@code RrfHybridChannel}）</li>
 *   <li>精排层：rerank 后决定送入 LLM 的最终数量（{@code RerankPostProcessor}）</li>
 * </ul>
 */
public final class ScoreClusterTopK {

    private ScoreClusterTopK() {
    }

    /**
     * @param scores       已按分数降序排列的 chunk 分数（可为 null，按"接近"保守处理）
     * @param baseMax      基准目标值（无显著落差信息时的参考值）
     * @param minScore     绝对底线，低于此分数的 chunk 直接排除
     * @param enabled      总开关，关闭时固定返回 baseMax
     * @param absGap       绝对落差阈值
     * @param tieBandRatio 相对接近度比例（相对最高分）
     * @param overflowCap  扩量上限
     * @return 应保留的 chunk 数量（≥ 1）
     */
    public static int compute(List<Float> scores, int baseMax, double minScore, boolean enabled,
                              double absGap, double tieBandRatio, int overflowCap) {
        return compute(scores, baseMax, minScore, enabled, absGap, tieBandRatio, overflowCap, 1, 1.5);
    }

    /**
     * 同上，额外指定缩量保底数量
     *
     * @param minChunks 缩量保底：即使分数分布判出明显落差，也至少保留前 minChunks 条，
     *                  避免"悬崖恰好把答案所在的接近群截在带外"（如 0.671 / 0.52,0.51,0.50 / 0.30…）
     */
    public static int compute(List<Float> scores, int baseMax, double minScore, boolean enabled,
                              double absGap, double tieBandRatio, int overflowCap, int minChunks) {
        return compute(scores, baseMax, minScore, enabled, absGap, tieBandRatio, overflowCap, minChunks, 1.5);
    }

    /**
     * 同上，额外指定悬崖比值阈值
     *
     * @param cliffRatio 悬崖比值阈值：相邻分数比值 s[i-1]/s[i] ≥ 该值 且 差值 ≥ 阈值 才判定为悬崖。
     *                   纯差值判定对分数尺度敏感（0-1 尺度下 0.15 的绝对差在第一名略高、后面成群的
     *                   "孤峰+平台"分布中频繁误判），叠加比值条件后对 0-1 / 0-100 等任意尺度鲁棒
     */
    public static int compute(List<Float> scores, int baseMax, double minScore, boolean enabled,
                              double absGap, double tieBandRatio, int overflowCap, int minChunks,
                              double cliffRatio) {
        if (scores == null || scores.isEmpty()) {
            return 0;
        }
        if (scores.size() <= baseMax) {
            return scores.size();
        }
        if (minChunks < 1) {
            minChunks = 1;
        }
        if (cliffRatio <= 1) {
            cliffRatio = 1.5;
        }

        // 分数尺度自适应：最高分都低于底线时（如 rerank 失败降级为 RRF 分数，量级 ~0.03），
        // 底线过滤会全灭候选，此时跳过过滤、按相对分布决策
        double rawTop = scores.stream()
                .filter(Objects::nonNull)
                .mapToDouble(Float::doubleValue)
                .max().orElse(0);
        boolean scaleDegraded = rawTop < minScore;

        List<Float> filtered = scaleDegraded
                ? scores
                : scores.stream()
                        .filter(s -> s == null || s >= minScore)
                        .toList();

        // 过滤回退：底线过滤后不足 minChunks 条时（分数压缩分布下答案可能刚好在底线以下），
        // 回退使用全量候选，避免答案 chunk 被硬过滤丢弃
        if (!scaleDegraded && filtered.size() < minChunks) {
            filtered = scores;
        }

        if (filtered.isEmpty()) {
            return 1;
        }
        if (filtered.size() <= baseMax) {
            return filtered.size();
        }
        if (!enabled) {
            return baseMax;
        }

        double topScore = filtered.get(0) != null ? filtered.get(0) : 0;
        // 显著落差阈值：绝对阈值与相对比例取较大者，适配不同模型的分数尺度
        double cliffThreshold = Math.max(absGap, tieBandRatio * topScore);

        // 1) 找第一个显著落差（差值 + 比值双条件，避免"孤峰+平台"分布误判）
        int cliff = -1;
        for (int i = 1; i < filtered.size(); i++) {
            Float s1 = filtered.get(i - 1);
            Float s2 = filtered.get(i);
            if (s1 == null || s2 == null) continue; // 未知分数按"接近"处理，不触发截断
            boolean gapHit = s1 - s2 >= cliffThreshold;
            boolean ratioHit = s2 > 0 && s1 / s2 >= cliffRatio;
            if (gapHit && ratioHit) {
                cliff = i;
                break;
            }
        }

        int count;
        if (cliff == -1) {
            // 2) 无显著落差 → 分数接近，按接近度扩量
            double cutoff = topScore * (1 - tieBandRatio);
            count = 0;
            for (Float s : filtered) {
                if (s == null || s >= cutoff) {
                    count++;
                } else {
                    break;
                }
            }
            if (count == 0) {
                count = 1;
            }
        } else {
            // 3) 有显著落差 → 截断在落差处（保底 minChunks 条）
            count = cliff;
        }

        // 结果钳制在 [minChunks, max(minChunks, overflowCap)] 区间：
        // 当配置出现 overflowCap < minChunks 的异常组合时，以 minChunks 为下限兜底，
        // 避免返回少于契约保证的条数（原 Math.min(count, overflowCap) 会被 overflowCap 拉低）
        return Math.min(Math.max(count, minChunks), Math.max(minChunks, overflowCap));
    }
}
