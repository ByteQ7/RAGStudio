package com.byteq.ai.ragstudio.ingestion.node;

import com.byteq.ai.ragstudio.core.chunk.strategy.FixedSizeTextChunker;
import com.byteq.ai.ragstudio.core.chunk.strategy.StructureAwareTextChunker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.core.chunk.ChunkEmbeddingService;
import com.byteq.ai.ragstudio.core.chunk.ChunkingOptions;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategyFactory;
import com.byteq.ai.ragstudio.core.chunk.ImageChunkGenerator;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategy;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.domain.settings.ChunkerSettings;
import com.byteq.ai.ragstudio.rag.constant.RAGConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final ImageChunkGenerator imageChunkGenerator;

    public ChunkerNode(ObjectMapper objectMapper,
                       ChunkingStrategyFactory chunkingStrategyFactory,
                       ChunkEmbeddingService chunkEmbeddingService,
                       ImageChunkGenerator imageChunkGenerator) {
        this.objectMapper = objectMapper;
        this.chunkingStrategyFactory = chunkingStrategyFactory;
        this.chunkEmbeddingService = chunkEmbeddingService;
        this.imageChunkGenerator = imageChunkGenerator;
    }

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

        // 多模态嵌入模型：为 PDF/图片文件额外生成图像块
        if (context.isSupportsImageEmbedding() && context.getRawBytes() != null) {
            List<VectorChunk> imageChunks = generateImageChunks(context);
            if (!imageChunks.isEmpty()) {
                chunks.addAll(imageChunks);
                log.info("生成了 {} 个图像块", imageChunks.size());
            }
        }

        // 为切分后的每个文本块/图像块生成向量嵌入，用于后续的相似度检索
        // 优先使用 IndexerNode settings 中配置的 embeddingModel（通过 IngestionEngine 预扫描写入 context）
        chunkEmbeddingService.embed(chunks, context.getEmbeddingModel());

        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    private List<VectorChunk> generateImageChunks(IngestionContext context) {
        String mimeType = context.getMimeType();
        byte[] rawBytes = context.getRawBytes();
        String docId = context.getTaskId();
        String bucketName = RAGConstant.S3_BUCKET_NAME;
        String baseKey = RAGConstant.S3_DOCUMENT_PREFIX + "/"
                + context.getVectorSpaceId().getLogicalName() + "/" + docId;

        if (mimeType == null) return List.of();

        if (isPdf(mimeType)) {
            return imageChunkGenerator.generateFromPdf(rawBytes, bucketName, baseKey, docId);
        }
        if (isImage(mimeType)) {
            VectorChunk chunk = imageChunkGenerator.generateFromImage(rawBytes, mimeType, bucketName, baseKey, docId, 0);
            if (chunk != null) {
                return List.of(chunk);
            }
        }
        return List.of();
    }

    private boolean isPdf(String mimeType) {
        return "pdf".equalsIgnoreCase(mimeType)
                || "application/pdf".equalsIgnoreCase(mimeType);
    }

    private boolean isImage(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.toLowerCase().startsWith("image/");
    }

    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        return settings.getStrategy().createDefaultOptions(
                settings.getChunkSize(), settings.getOverlapSize());
    }

    private List<VectorChunk> convertToVectorChunks(List<VectorChunk> results) {
        return results.stream()
                .map(result -> VectorChunk.builder()
                        .chunkId(result.getChunkId())
                        .index(result.getIndex())
                        .content(result.getContent())
                        .metadata(result.getMetadata())
                        .embedding(result.getEmbedding())
                        .contentType("TEXT")
                        .build())
                .collect(Collectors.toList());
    }

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
