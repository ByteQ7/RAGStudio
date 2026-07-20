package com.byteq.ai.ragstudio.infra.highlight;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语义高亮服务请求体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticHighlightRequest {

    private String question;
    private List<ChunkItem> chunks;
    private Double threshold;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkItem {
        private String id;
        private String text;
    }
}
