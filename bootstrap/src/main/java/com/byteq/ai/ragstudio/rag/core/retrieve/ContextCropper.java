package com.byteq.ai.ragstudio.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.highlight.SemanticHighlightClient;
import com.byteq.ai.ragstudio.infra.highlight.SemanticHighlightRequest;
import com.byteq.ai.ragstudio.infra.highlight.SemanticHighlightResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文裁剪器
 * <p>
 * 在检索 Chunk 之后、发给 LLM 之前，用语义高亮模型对每个 Chunk 逐句打分，
 * 只保留与用户问题相关的句子，裁剪无关内容。
 * 这相当于在 RAG pipeline 中插入一道 context pruning 步骤。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCropper {

    private static final double DEFAULT_THRESHOLD = 0.3;

    private final SemanticHighlightClient semanticHighlightClient;

    /**
     * 裁剪 Chunk 列表：只保留与问题语义相关的句子
     *
     * @param question 用户问题（建议使用 rewrite 后的 query）
     * @param chunks   待裁剪的 Chunk 列表（直接修改其 text 字段）
     */
    @RagTraceNode(name = "语义裁剪", type = "CROP")
    public void crop(String question, List<RetrievedChunk> chunks) {
        if (!semanticHighlightClient.isEnabled()) {
            log.debug("语义高亮服务未启用，跳过裁剪");
            return;
        }
        if (CollUtil.isEmpty(chunks) || question == null || question.isBlank()) {
            return;
        }

        List<SemanticHighlightRequest.ChunkItem> chunkItems = chunks.stream()
                .map(c -> SemanticHighlightRequest.ChunkItem.builder()
                        .id(c.getId())
                        .text(c.getText())
                        .build())
                .toList();

        SemanticHighlightResponse response;
        try {
            response = semanticHighlightClient.highlight(question, chunkItems, DEFAULT_THRESHOLD);
        } catch (Exception e) {
            log.warn("语义裁剪失败，保留原文: {}", e.getMessage());
            return;
        }

        if (response == null || CollUtil.isEmpty(response.getResults())) {
            return;
        }

        for (SemanticHighlightResponse.ChunkHighlightResult result : response.getResults()) {
            if (result == null) continue;
            List<Integer> highlightedIndices = result.getHighlightedIndices();
            List<String> sentences = result.getSentences();

            if (CollUtil.isEmpty(highlightedIndices) || CollUtil.isEmpty(sentences)) {
                continue;
            }

            // 只保留分数 >= threshold 的句子
            String cropped = highlightedIndices.stream()
                    .filter(idx -> idx >= 0 && idx < sentences.size())
                    .map(sentences::get)
                    .collect(Collectors.joining());

            if (cropped.isBlank()) {
                continue;  // 裁剪后为空则保留原文
            }

            // 找到对应的 chunk 并替换文本
            for (RetrievedChunk chunk : chunks) {
                if (chunk.getId() != null && chunk.getId().equals(result.getChunkId())) {
                    log.debug("裁剪 Chunk {}: {} 句 → {} 句",
                            chunk.getId(), sentences.size(), highlightedIndices.size());
                    chunk.setText(cropped);
                    break;
                }
            }
        }
    }
}
