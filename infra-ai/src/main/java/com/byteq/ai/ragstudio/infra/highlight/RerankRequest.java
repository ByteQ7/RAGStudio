package com.byteq.ai.ragstudio.infra.highlight;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语义重排序请求体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankRequest {

    private String question;
    private List<SemanticHighlightRequest.ChunkItem> chunks;
}
