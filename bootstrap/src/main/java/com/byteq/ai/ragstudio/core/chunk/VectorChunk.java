package com.byteq.ai.ragstudio.core.chunk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.byteq.ai.ragstudio.framework.convention.ChunkType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 分块结果对象
 * 统一的分块输出格式，包含所有必要信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorChunk {

    /**
     * 块的唯一标识符
     */
    private String chunkId;

    /**
     * 块在文档中的序号索引，从0开始
     */
    private Integer index;

    /**
     * 块的原始文本内容
     */
    private String content;

    /**
     * 块的元数据信息
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 块的向量嵌入表示
     * 用于向量相似度检索的浮点数数组
     */
    @JsonIgnore
    private float[] embedding;

    /**
     * 内容类型: TEXT / IMAGE
     * TEXT 为普通文本块，IMAGE 为图像块（content 可为空或占位描述，实际图片通过 metadata.image_url 获取）
     */
    @Builder.Default
    private String contentType = "TEXT";

    public ChunkType getType() {
        return ChunkType.from(contentType);
    }

    public boolean isType(ChunkType type) {
        return getType() == type;
    }

    public boolean isImage() { return getType() == ChunkType.IMAGE; }
}
