package com.byteq.ai.ragstudio.infra.highlight;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语义重排序响应体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankResponse {

    private List<RerankItem> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RerankItem {
        private String id;
        private double score;
        private int index;
    }
}
