package com.byteq.ai.ragstudio.core.chunk;

import com.byteq.ai.ragstudio.core.chunk.strategy.FixedSizeTextChunker;
import com.byteq.ai.ragstudio.core.chunk.strategy.StructureAwareTextChunker;

import java.util.List;

/**
 * 文本分块策略核心接口
 * <p>
 * 定义统一的文本分块能力，采用策略模式实现。不同的分块算法通过实现该接口
 * 来提供各自的分块策略，由 {@link ChunkingStrategyFactory} 统一管理。
 * </p>
 * <p>
 * 系统内置了以下分块策略：
 * <ul>
 *   <li>{@link FixedSizeTextChunker} - 固定大小分块
 *   （按字符数切分，支持重叠）</li>
 *   <li>{@link StructureAwareTextChunker} - 结构感知分块
 *   （保留 Markdown/文本结构，在语义边界处切分）</li>
 * </ul>
 * </p>
 *
 * @see ChunkingMode
 * @see ChunkingOptions
 * @see VectorChunk
 */
public interface ChunkingStrategy {

    /**
     * 获取分块策略类型标识
     *
     * @return 分块策略类型枚举值
     */
    ChunkingMode getType();

    /**
     * 对文本执行分块处理
     * <p>
     * 根据配置的分块参数（块大小、重叠大小等），将输入的长文本切分为
     * 多个较小的文本块。每个文本块包含文本内容和其在原文中的位置索引。
     * </p>
     *
     * @param text   待分块的原始文本内容
     * @param config 分块配置参数，包含块大小、重叠大小等策略特定参数
     * @return 分块后的文本块列表
     */
    List<VectorChunk> chunk(String text, ChunkingOptions config);
}
