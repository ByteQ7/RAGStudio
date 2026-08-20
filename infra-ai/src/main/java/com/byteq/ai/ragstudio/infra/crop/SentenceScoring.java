package com.byteq.ai.ragstudio.infra.crop;

/**
 * 句子打分结果
 *
 * @param sentences         参与打分的句子列表（与输入一致）
 * @param scores            每个句子与查询的余弦相似度
 * @param highlightedIndices 分数 ≥ 阈值的句子索引（升序）
 */
public record SentenceScoring(java.util.List<String> sentences, double[] scores, int[] highlightedIndices) {
}