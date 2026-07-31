package com.byteq.ai.ragstudio.rag.core.retrieve.postprocessor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分数簇感知动态 TopK 纯函数测试
 */
class RerankPostProcessorDynamicTopKTests {

    private static final int BASE_MAX = 5;
    private static final double MIN_SCORE = 0.3;
    private static final double ABS_GAP = 0.15;
    private static final double TIE_BAND = 0.15;
    private static final int OVERFLOW_CAP = 10;

    private int run(List<Float> scores) {
        return RerankPostProcessor.computeDynamicTopK(
                scores, BASE_MAX, MIN_SCORE, true, ABS_GAP, TIE_BAND, OVERFLOW_CAP);
    }

    @Test
    void 少于基准值_全量返回() {
        assertEquals(4, run(List.of(0.95f, 0.90f, 0.80f, 0.70f)));
    }

    @Test
    void 分数接近_扩量保留正确Chunk() {
        // 8 个分数接近（相邻差 0.01），正确 chunk 在第 6 个，不应截断在 5
        List<Float> scores = List.of(
                0.93f, 0.92f, 0.91f, 0.90f, 0.89f, 0.88f, 0.87f, 0.86f, 0.40f, 0.39f);
        assertEquals(8, run(scores));
    }

    @Test
    void 前几个远大于后面_缩量节省Token() {
        // 前 2 个远大于后面的，只取 2 个
        List<Float> scores = List.of(
                0.95f, 0.93f, 0.45f, 0.44f, 0.43f, 0.42f, 0.41f, 0.40f, 0.39f, 0.38f);
        assertEquals(2, run(scores));
    }

    @Test
    void 单一绝对高分_只取1个() {
        List<Float> scores = List.of(0.95f, 0.50f, 0.49f, 0.48f, 0.47f, 0.46f);
        assertEquals(1, run(scores));
    }

    @Test
    void 渐进下降_按接近度带截断() {
        // 相邻差 0.05 无显著落差，接近带 cutoff = 0.95×0.85 = 0.8075 → 取 3 个（0.80 低于带下界）
        List<Float> scores = List.of(
                0.95f, 0.90f, 0.85f, 0.80f, 0.75f, 0.70f, 0.65f, 0.60f);
        assertEquals(3, run(scores));
    }

    @Test
    void 全等分_扩量到上限() {
        List<Float> scores = List.of(
                0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f);
        assertEquals(10, run(scores));
    }

    @Test
    void 落差出现在基准值之后_取到落差处() {
        // 前 7 个接近，第 8 个起骤降 → 取 7（扩量到落差处）
        List<Float> scores = List.of(
                0.92f, 0.91f, 0.90f, 0.89f, 0.88f, 0.87f, 0.86f, 0.30f, 0.29f, 0.28f);
        assertEquals(7, run(scores));
    }

    @Test
    void 全部低于底线_保底1个() {
        List<Float> scores = List.of(0.2f, 0.15f, 0.1f, 0.05f, 0.02f, 0.01f);
        assertEquals(1, run(scores));
    }

    @Test
    void 最高分低于底线_跳过底线过滤按相对分布() {
        // rerank 降级为 RRF 分数（~0.03 量级），最高分 < 0.3 → 不做底线过滤
        // 无显著落差，接近带 cutoff = 0.032×0.85 = 0.0272 → 取 4 个
        List<Float> scores = List.of(
                0.032f, 0.031f, 0.030f, 0.029f, 0.020f, 0.015f);
        assertEquals(4, run(scores));
    }

    @Test
    void 悬崖恰好截断答案群_保底minChunks() {
        // 0.671 后紧跟接近群 0.52/0.51/0.50（gap 0.151 ≥ 0.15 判为落差），minChunks=3 保底 → 取 3
        List<Float> scores = List.of(
                0.671f, 0.520f, 0.510f, 0.500f, 0.450f, 0.440f, 0.300f, 0.290f, 0.280f);
        int result = RerankPostProcessor.computeDynamicTopK(
                scores, BASE_MAX, MIN_SCORE, true, ABS_GAP, TIE_BAND, OVERFLOW_CAP, 3);
        assertEquals(3, result);
    }

    @Test
    void 底线过滤后不足minChunks_回退全量候选() {
        // 22 条中仅 1 条 ≥ 0.3（分数压缩分布），过滤回退全量 → 取前 3（0.671 + 紧跟两条）
        List<Float> scores = List.of(
                0.671f, 0.280f, 0.270f, 0.260f, 0.250f, 0.240f, 0.230f,
                0.220f, 0.210f, 0.200f, 0.190f, 0.180f, 0.170f, 0.160f,
                0.150f, 0.140f, 0.130f, 0.120f, 0.110f, 0.100f, 0.090f, 0.080f);
        int result = RerankPostProcessor.computeDynamicTopK(
                scores, BASE_MAX, MIN_SCORE, true, ABS_GAP, TIE_BAND, OVERFLOW_CAP, 3);
        assertEquals(3, result);
    }

    @Test
    void 明确悬崖_保底minChunks仍然生效() {
        // 0.95 远大于后面（正确 chunk 在第 1 位），minChunks=3 时保底 3 条，不退回"只取 1"
        List<Float> scores = List.of(0.95f, 0.50f, 0.49f, 0.48f, 0.47f, 0.46f);
        int result = RerankPostProcessor.computeDynamicTopK(
                scores, BASE_MAX, MIN_SCORE, true, ABS_GAP, TIE_BAND, OVERFLOW_CAP, 3);
        assertEquals(3, result);
    }

    @Test
    void 孤峰加平台_不误判悬崖_接近带内扩量() {
        // 0.671 后紧跟平台 0.52/0.51/0.50：差值 0.151 越阈值但比值 1.29 < 1.5 → 非悬崖
        // 接近带 cutoff = 0.671×0.85 = 0.570 → 只有孤峰 1 条在带内，minChunks=3 保底取 3
        List<Float> scores = List.of(
                0.671f, 0.520f, 0.510f, 0.500f, 0.450f, 0.440f, 0.300f, 0.290f, 0.280f);
        int result = RerankPostProcessor.computeDynamicTopK(
                scores, BASE_MAX, MIN_SCORE, true, ABS_GAP, TIE_BAND, OVERFLOW_CAP, 3);
        assertEquals(3, result);
    }

    @Test
    void 孤峰加平台_minChunks为1时_按带取孤峰() {
        // 差值越阈值但比值不够 → 非悬崖，minChunks=1 时带内仅孤峰 1 条 → 取 1
        List<Float> scores = List.of(
                0.671f, 0.520f, 0.510f, 0.500f, 0.450f, 0.440f, 0.300f, 0.290f, 0.280f);
        int result = RerankPostProcessor.computeDynamicTopK(
                scores, BASE_MAX, MIN_SCORE, true, ABS_GAP, TIE_BAND, OVERFLOW_CAP, 1);
        assertEquals(1, result);
    }

    @Test
    void 悬崖比值确认_仍可截断() {
        // 0.95 → 0.45：差值 0.50 ≥ 0.15 且比值 2.1 ≥ 1.5 → 悬崖，minChunks=1 截断在 1
        List<Float> scores = List.of(0.95f, 0.45f, 0.44f, 0.43f, 0.42f, 0.41f, 0.40f);
        int result = RerankPostProcessor.computeDynamicTopK(
                scores, BASE_MAX, MIN_SCORE, true, ABS_GAP, TIE_BAND, OVERFLOW_CAP, 1);
        assertEquals(1, result);
    }

    @Test
    void 关闭开关_固定返回基准值() {
        int result = RerankPostProcessor.computeDynamicTopK(
                List.of(0.95f, 0.93f, 0.45f, 0.44f, 0.43f, 0.42f, 0.41f, 0.40f),
                BASE_MAX, MIN_SCORE, false, ABS_GAP, TIE_BAND, OVERFLOW_CAP);
        assertEquals(5, result);
    }

    @Test
    void 含null分数_按接近处理不误截断() {
        List<Float> scores = new java.util.ArrayList<>();
        scores.add(0.95f);
        scores.add(null);
        scores.add(0.50f);
        scores.add(0.49f);
        scores.add(0.48f);
        scores.add(0.47f);
        // null 与 0.95 视为接近，不触发截断；0.50 掉出接近带
        assertEquals(2, run(scores));
    }
}
