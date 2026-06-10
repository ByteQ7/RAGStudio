package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索通道结果
 * <p>
 * 封装单个通道的检索结果及元信息
 */
@Data
@Builder
public class SearchChannelResult {

    /**
     * 通道类型
     */
    private SearchChannelType channelType;

    /**
     * 通道名称
     */
    private String channelName;

    /**
     * 检索到的 Chunk 列表
     */
    private List<RetrievedChunk> chunks;

    /**
     * 检索耗时（毫秒）
     */
    private long latencyMs;

    /**
     * 扩展元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
