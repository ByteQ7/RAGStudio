package com.byteq.ai.ragstudio.infra.highlight;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语义高亮服务响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticHighlightResponse {

    private List<ChunkHighlightResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkHighlightResult {
        @JsonProperty("chunk_id")
        private String chunkId;
        private List<String> sentences;
        private List<Double> scores;
        @JsonProperty("highlighted_indices")
        private List<Integer> highlightedIndices;
        @JsonProperty("highlighted_sentences")
        private List<String> highlightedSentences;
    }
}
