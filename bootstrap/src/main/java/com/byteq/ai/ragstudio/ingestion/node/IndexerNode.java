package com.byteq.ai.ragstudio.ingestion.node;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.byteq.ai.ragstudio.rag.config.RAGDefaultProperties;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.domain.settings.IndexerSettings;
import com.byteq.ai.ragstudio.rag.core.vector.VectorSpaceId;
import com.byteq.ai.ragstudio.rag.core.vector.VectorSpaceSpec;
import com.byteq.ai.ragstudio.rag.core.vector.VectorStoreAdmin;
import com.byteq.ai.ragstudio.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量索引节点（IndexerNode）
 * <p>
 * 数据摄入流水线的最后一个处理节点，负责将 ChunkerNode 处理后的文档分块数据
 * 索引到向量数据库中。这是 RAG（检索增强生成）系统的数据入库环节。
 * </p>
 * <p>
 * 核心处理逻辑：
 * <ol>
 *   <li>验证上下文中的文本块数据和向量嵌入的完整性</li>
 *   <li>解析索引配置（集合名称、元数据字段映射等）</li>
 *   <li>确保目标向量空间（Collection）已存在，不存在则自动创建</li>
 *   <li>构建向量数据行，包含文本内容、向量嵌入和元数据</li>
 *   <li>批量写入向量数据库</li>
 * </ol>
 * </p>
 * <p>
 * 特性说明：
 * <ul>
 *   <li>支持事务性写入：可通过 {@link IngestionContext#isSkipIndexerWrite()} 控制是否延迟写入</li>
 *   <li>向量维度自动检测：优先使用配置维度，其次从已有嵌入数据推断</li>
 *   <li>元数据过滤：支持选择性的元数据字段写入，减少存储开销</li>
 *   <li>内容长度保护：自动截断超长文本（超过 65535 字符）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    private static final Gson GSON = new Gson();

    /**
     * Jackson JSON 对象映射器，用于解析节点配置
     */
    private final ObjectMapper objectMapper;

    /**
     * 向量存储管理服务，用于创建和管理向量空间
     */
    private final VectorStoreAdmin vectorStoreAdmin;

    /**
     * 向量存储服务，用于执行向量数据的写入操作
     */
    private final VectorStoreService vectorStoreService;

    /**
     * RAG 默认配置属性，包含默认集合名称和向量维度等配置
     */
    private final RAGDefaultProperties ragDefaultProperties;

    public IndexerNode(ObjectMapper objectMapper,
                       VectorStoreAdmin vectorStoreAdmin,
                       VectorStoreService vectorStoreService,
                       RAGDefaultProperties ragDefaultProperties) {
        this.objectMapper = objectMapper;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.vectorStoreService = vectorStoreService;
        this.ragDefaultProperties = ragDefaultProperties;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.INDEXER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("没有可索引的分块"));
        }
        IndexerSettings settings = parseSettings(config.getSettings());
        String collectionName = resolveCollectionName(context);

        // 记录配置的 embedding 模型信息，便于排查
        if (StringUtils.hasText(settings.getEmbeddingModel())) {
            log.info("IndexerNode 配置的 embeddingModel: {}", settings.getEmbeddingModel());
        }
        if (!StringUtils.hasText(collectionName)) {
            return NodeResult.fail(new ClientException("索引器需要指定集合名称"));
        }

        int expectedDim = resolveDimension(chunks);
        if (expectedDim <= 0) {
            return NodeResult.fail(new ClientException("未配置向量维度"));
        }
        float[][] vectorArray;
        try {
            vectorArray = toArrayFromChunks(chunks, expectedDim);
        } catch (ClientException ex) {
            return NodeResult.fail(ex);
        }

        // 确保目标向量空间存在，不存在时自动创建
        ensureVectorSpace(collectionName);
        List<JsonObject> rows = buildRows(context, chunks, vectorArray, settings.getMetadataFields());

        // 支持事务性写入：如果调用方要求延迟写入，则只做校验和数据准备
        if (context.isSkipIndexerWrite()) {
            return NodeResult.ok("已准备 " + rows.size() + " 个分块（向量写入由调用方统一完成）");
        }

        insertRows(collectionName, context.getTaskId(), rows);
        return NodeResult.ok("已写入 " + rows.size() + " 个分块到集合 " + collectionName);
    }

    /**
     * 解析节点配置中的 settings JSON 为 IndexerSettings 对象
     *
     * @param node JSON 配置节点
     * @return 索引器设置对象
     */
    private IndexerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }

    /**
     * 解析向量集合名称
     * <p>
     * 集合名称由触发流水线知识库决定，不从节点配置中读取。
     * 优先级从高到低：
     * <ol>
     *   <li>上下文中的向量空间 ID 的逻辑名称（由知识库触发时设置）</li>
     *   <li>默认配置中的集合名称</li>
     * </ol>
     * </p>
     *
     * @param context 摄入上下文
     * @return 向量集合名称
     */
    private String resolveCollectionName(IngestionContext context) {
        // 优先使用上下文中的向量空间 ID（由触发流水线的知识库决定）
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            return context.getVectorSpaceId().getLogicalName();
        }
        // 最后使用默认配置
        return ragDefaultProperties.getCollectionName();
    }

    /**
     * 确保目标向量空间存在
     * <p>
     * 检查指定名称的向量集合是否已存在，不存在时自动创建。
     * </p>
     *
     * @param collectionName 向量集合名称
     */
    private void ensureVectorSpace(String collectionName) {
        boolean vectorSpaceExists = vectorStoreAdmin.vectorSpaceExists(VectorSpaceId.builder()
                .logicalName(collectionName)
                .build());
        if (vectorSpaceExists) {
            return;
        }

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(collectionName)
                        .build())
                .remark("RAG向量存储空间")
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);
    }

    /**
     * 批量写入向量数据到数据库
     *
     * @param collectionName 目标向量集合名称
     * @param docId          文档 ID
     * @param rows           向量数据行列表
     */
    private void insertRows(String collectionName, String docId, List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<VectorChunk> chunks = rows.stream().map(row -> {
            String chunkId = row.get("id").getAsString();
            String content = row.get("content").getAsString();
            JsonArray embeddingArray = row.getAsJsonArray("embedding");
            float[] embedding = new float[embeddingArray.size()];
            for (int i = 0; i < embeddingArray.size(); i++) {
                embedding[i] = embeddingArray.get(i).getAsFloat();
            }

            Integer chunkIndex = null;
            if (row.has("metadata") && row.get("metadata").isJsonObject()) {
                JsonObject metadata = row.getAsJsonObject("metadata");
                if (metadata.has("chunk_index")) {
                    chunkIndex = metadata.get("chunk_index").getAsInt();
                }
            }

            return VectorChunk.builder()
                    .chunkId(chunkId)
                    .content(content)
                    .index(chunkIndex)
                    .embedding(embedding)
                    .build();
        }).toList();

        vectorStoreService.indexDocumentChunks(collectionName, docId, chunks);

        log.info("向量写入成功，集合={}，行数={}", collectionName, chunks.size());
    }

    /**
     * 解析向量维度
     * <p>
     * 优先使用默认配置中的维度值，其次从已有文本块的嵌入数据中推断。
     * </p>
     *
     * @param chunks 文本块列表
     * @return 向量维度，如果无法确定则返回 0
     */
    private int resolveDimension(List<VectorChunk> chunks) {
        Integer configured = ragDefaultProperties.getDimension();
        if (configured != null && configured > 0) {
            return configured;
        }
        for (VectorChunk chunk : chunks) {
            if (chunk.getEmbedding() != null && chunk.getEmbedding().length > 0) {
                return chunk.getEmbedding().length;
            }
        }
        return 0;
    }

    /**
     * 将文本块的向量嵌入提取为二维 float 数组
     *
     * @param chunks      文本块列表
     * @param expectedDim 期望的向量维度
     * @return 二维 float 数组
     * @throws ClientException 如果向量缺失或维度不匹配
     */
    private float[][] toArrayFromChunks(List<VectorChunk> chunks, int expectedDim) {
        float[][] out = new float[chunks.size()][];
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = chunks.get(i).getEmbedding();
            if (vector == null || vector.length == 0) {
                throw new ClientException("向量结果缺失，索引: " + i);
            }
            if (expectedDim > 0 && vector.length != expectedDim) {
                throw new ClientException("向量维度不匹配，索引: " + i);
            }
            out[i] = vector;
        }
        return out;
    }

    /**
     * 构建向量数据行
     * <p>
     * 将文本块数据、向量嵌入和元数据组装为数据库写入所需的 JSON 行格式。
     * </p>
     *
     * @param context        摄入上下文
     * @param chunks         文本块列表
     * @param vectors        向量嵌入数组
     * @param metadataFields 需要写入的元数据字段列表
     * @return JSON 行列表
     */
    private List<JsonObject> buildRows(IngestionContext context,
                                       List<VectorChunk> chunks,
                                       float[][] vectors,
                                       List<String> metadataFields) {
        Map<String, Object> mergedMetadata = mergeMetadata(context);
        List<JsonObject> rows = new java.util.ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            VectorChunk chunk = chunks.get(i);
            String chunkId = StringUtils.hasText(chunk.getChunkId()) ? chunk.getChunkId() : IdUtil.getSnowflakeNextIdStr();
            chunk.setChunkId(chunkId);
            chunk.setEmbedding(vectors[i]);

            // 限制存储内容长度，避免超出数据库字段限制
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (content.length() > 65535) {
                content = content.substring(0, 65535);
            }

            JsonObject metadata = new JsonObject();
            metadata.addProperty("chunk_index", chunk.getIndex());
            metadata.addProperty("task_id", context.getTaskId());
            metadata.addProperty("pipeline_id", context.getPipelineId());
            DocumentSource source = context.getSource();
            if (source != null && source.getType() != null) {
                metadata.addProperty("source_type", source.getType().getValue());
            }
            if (source != null && StringUtils.hasText(source.getLocation())) {
                metadata.addProperty("source_location", source.getLocation());
            }

            // 选择性写入配置的元数据字段
            if (metadataFields != null && !metadataFields.isEmpty()) {
                Map<String, Object> combined = new HashMap<>(mergedMetadata);
                if (chunk.getMetadata() != null) {
                    combined.putAll(chunk.getMetadata());
                }
                for (String field : metadataFields) {
                    if (!StringUtils.hasText(field)) {
                        continue;
                    }
                    Object value = combined.get(field);
                    if (value != null) {
                        addMetadataValue(metadata, field, value);
                    }
                }
            }

            JsonObject row = new JsonObject();
            row.addProperty("id", chunkId);
            row.addProperty("content", content);
            row.add("metadata", metadata);
            row.add("embedding", toJsonArray(vectors[i]));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 合并上下文中的全局元数据
     *
     * @param context 摄入上下文
     * @return 合并后的元数据映射
     */
    private Map<String, Object> mergeMetadata(IngestionContext context) {
        Map<String, Object> merged = new HashMap<>();
        if (context.getMetadata() != null) {
            merged.putAll(context.getMetadata());
        }
        return merged;
    }

    /**
     * 将元数据值添加到 JSON 对象中
     *
     * @param metadata 目标 JSON 元数据对象
     * @param field    字段名
     * @param value    字段值
     */
    private void addMetadataValue(JsonObject metadata, String field, Object value) {
        JsonElement element = GSON.toJsonTree(value);
        metadata.add(field, element);
    }

    /**
     * 将 float 向量数组转换为 Gson JsonArray
     *
     * @param vector float 向量数组
     * @return Gson JsonArray
     */
    private JsonArray toJsonArray(float[] vector) {
        JsonArray arr = new JsonArray(vector.length);
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }
}
