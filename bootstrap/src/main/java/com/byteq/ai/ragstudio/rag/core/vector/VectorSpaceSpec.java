package com.byteq.ai.ragstudio.rag.core.vector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorSpaceSpec {

    /**
     * 向量空间标识
     */
    private VectorSpaceId spaceId;

    /**
     * 向量维度，对应 t_knowledge_vector_{dimension} 表（如 1024、1536，≤ 2000）
     */
    private int dimension;

    /**
     * 备注
     */
    private String remark;
}
