package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * per-KB RRF 融合后的分数簇感知截断测试
 */
class RrfHybridChannelPerKbTruncationTests {

    private List<RetrievedChunk> chunks(float... scores) {
        List<RetrievedChunk> list = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            list.add(RetrievedChunk.builder()
                    .id("chunk_" + i)
                    .text("text_" + i)
                    .score(scores[i])
                    .build());
        }
        return list;
    }

    @Test
    void 不超过基准值_不截断() {
        List<RetrievedChunk> chunks = chunks(0.032f, 0.030f, 0.028f, 0.027f);
        assertEquals(4, RrfHybridChannel.applyPerKbClusterTruncation(chunks, 5, 15).size());
    }

    @Test
    void 分数接近_保留全部接近带内chunk() {
        // 12 条分数接近（相邻差 0.0004），全部落在最高分 15% 接近带内 → 全部保留
        float[] scores = new float[12];
        for (int i = 0; i < 12; i++) {
            scores[i] = 0.032f - i * 0.0004f;
        }
        assertEquals(12, RrfHybridChannel.applyPerKbClusterTruncation(chunks(scores), 5, 15).size());
    }

    @Test
    void 分数接近_超过扩量上限封顶() {
        // 20 条接近（相邻差 0.0001）→ 全部在接近带内，封顶 15
        float[] scores = new float[20];
        for (int i = 0; i < 20; i++) {
            scores[i] = 0.03f - i * 0.0001f;
        }
        assertEquals(15, RrfHybridChannel.applyPerKbClusterTruncation(chunks(scores), 5, 15).size());
    }

    @Test
    void 前几条远大于后面_截断在落差处() {
        // 前 2 条 RRF 分数显著高于后面 → 只保留 2 条，不硬凑 5 条
        List<RetrievedChunk> chunks = chunks(
                0.032f, 0.031f, 0.005f, 0.004f, 0.003f, 0.002f);
        assertEquals(2, RrfHybridChannel.applyPerKbClusterTruncation(chunks, 5, 15).size());
    }

    @Test
    void 空列表_安全返回() {
        assertEquals(0, RrfHybridChannel.applyPerKbClusterTruncation(List.of(), 5, 15).size());
    }
}
