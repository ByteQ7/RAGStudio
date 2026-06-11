package com.byteq.ai.ragstudio.ingestion.node;

import com.byteq.ai.ragstudio.core.chunk.strategy.FixedSizeTextChunker;
import com.byteq.ai.ragstudio.core.chunk.strategy.StructureAwareTextChunker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.core.chunk.ChunkEmbeddingService;
import com.byteq.ai.ragstudio.core.chunk.ChunkingOptions;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategyFactory;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategy;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.domain.settings.ChunkerSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文本分块节点（ChunkerNode）
 * <p>
 * 数据摄入流水线的核心处理节点，负责将 ParserNode 解析后的完整文本按照指定的策略
 * 切分成多个较小的文本块（Chunk）。分块是 RAG（检索增强生成）系统的关键步骤，
 * 合理的分块策略直接影响后续的检索效果。
 * </p>
 * <p>
 * 核心处理逻辑：
 * <ol>
 *   <li>优先使用增强后的文本（enhancedText），否则使用原始解析文本（rawText）</li>
 *   <li>根据流水线配置选择分块策略（固定大小分块或结构感知分块）</li>
 *   <li>执行分块操作，将长文本切分为多个文本块</li>
 *   <li>为每个文本块生成向量嵌入（embedding），用于后续的向量检索</li>
 * </ol>
 * </p>
 * <p>
 * 支持的分块策略：
 * <ul>
 *   <li>{@link FixedSizeTextChunker} - 固定大小分块，按字符数切分</li>
 *   <li>{@link StructureAwareTextChunker} - 结构感知分块，保留文档结构</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    /**
     * Jackson JSON 对象映射器，用于解析节点配置
     */
    private final ObjectMapper objectMapper;

    /**
     * 分块策略工厂，根据配置选择对应的分块实现
     */
    private final ChunkingStrategyFactory chunkingStrategyFactory;

    /**
     * 文本块向量嵌入服务，为切分后的文本块生成向量表示
     */
    private final ChunkEmbeddingService chunkEmbeddingService;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        String text = StringUtils.hasText(context.getEnhancedText()) ? context.getEnhancedText() : context.getRawText();
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail(new ClientException("可分块文本为空"));
        }
        ChunkerSettings settings = parseSettings(config.getSettings());
        ChunkingStrategy chunker = chunkingStrategyFactory.requireStrategy(settings.getStrategy());
        if (chunker == null) {
            return NodeResult.fail(new ClientException("未找到分块策略: " + settings.getStrategy()));
        }

        ChunkingOptions chunkConfig = convertToChunkConfig(settings);
        List<VectorChunk> results = chunker.chunk(text, chunkConfig);
        List<VectorChunk> chunks = convertToVectorChunks(results);

        // 为切分后的每个文本块生成向量嵌入，用于后续的相似度检索
        // 优先使用 IndexerNode settings 中配置的 embeddingModel（通过 IngestionEngine 预扫描写入 context）
        chunkEmbeddingService.embed(chunks, context.getEmbeddingModel());

        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    /**
     * 将分块设置转换为分块配置对象
     *
     * @param settings 分块设置，包含策略类型、块大小和重叠大小
     * @return 分块配置对象
     */
    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        return settings.getStrategy().createDefaultOptions(
                settings.getChunkSize(), settings.getOverlapSize());
    }

    /**
     * 将分块结果转换为 VectorChunk 列表
     *
     * @param results 原始分块结果
     * @return 转换后的 VectorChunk 列表
     */
    private List<VectorChunk> convertToVectorChunks(List<VectorChunk> results) {
        return results.stream()
                .map(result -> VectorChunk.builder()
                        .chunkId(result.getChunkId())
                        .index(result.getIndex())
                        .content(result.getContent())
                        .metadata(result.getMetadata())
                        .embedding(result.getEmbedding())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 解析节点配置中的 settings JSON 为 ChunkerSettings 对象
     * <p>
     * 如果配置中未指定分块大小或重叠大小，使用默认值（分块大小=512，重叠大小=128）。
     * </p>
     *
     * @param node JSON 配置节点
     * @return 分块设置对象
     */
    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
