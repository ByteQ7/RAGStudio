package com.byteq.ai.ragstudio.rag.core.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 pgvector 的向量存储服务实现
 * <p>
 * 向量按维度分表存储，表名 t_knowledge_vector_{dimension}。
 * 写入前校验每个 chunk 的 embedding 维度是否与指定 dimension 一致。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreService implements VectorStoreService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final VectorStoreAdmin vectorStoreAdmin;

    @Override
    public void indexDocumentChunks(String collectionName, String docId, int dimension, List<VectorChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        int actualDim = chunks.get(0).getEmbedding() != null ? chunks.get(0).getEmbedding().length : dimension;
        if (actualDim != dimension) {
            log.warn("Embedding 实际维度 {} 与 KB 配置维度 {} 不一致", actualDim, dimension);
            dimension = actualDim;
        }

        // 确保向量表存在（>2000维会跳过HNSW索引）
        autoCreateVectorTable(collectionName, dimension);

        String table = vectorTableName(dimension);
        for (VectorChunk chunk : chunks) {
            if (chunk.getEmbedding() == null || chunk.getEmbedding().length != dimension) {
                throw new IllegalArgumentException(String.format(
                        "Embedding 维度不匹配: 期望 %d 维，实际 %s 维",
                        dimension, chunk.getEmbedding() == null ? "null" : chunk.getEmbedding().length));
            }
        }

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.batchUpdate(
                "INSERT INTO " + table + " (id, content, metadata, embedding, content_type) VALUES (?, ?, ?::jsonb, ?::vector, ?)",
                chunks, chunks.size(), (ps, chunk) -> {
                    ps.setString(1, chunk.getChunkId());
                    ps.setString(2, chunk.getContent());
                    ps.setString(3, buildMetadataJson(collectionName, docId, chunk));
                    ps.setString(4, toVectorLiteral(chunk.getEmbedding()));
                    ps.setString(5, chunk.getContentType() != null ? chunk.getContentType() : "TEXT");
                });

        log.info("批量写入向量到 {}，collectionName={}, docId={}, count={}", table, collectionName, docId, chunks.size());
    }

    @Override
    public void deleteDocumentVectors(String collectionName, String docId, int dimension) {
        String table = vectorTableName(dimension);
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE metadata->>'collection_name' = ? AND metadata->>'doc_id' = ?",
                collectionName, docId);
        log.info("删除文档向量，table={}, collectionName={}, docId={}, deleted={}", table, collectionName, docId, deleted);
    }

    @Override
    public void deleteChunkById(int dimension, String chunkId) {
        String table = vectorTableName(dimension);
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.update("DELETE FROM " + table + " WHERE id = ?", chunkId);
    }

    @Override
    public void deleteChunksByIds(int dimension, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String table = vectorTableName(dimension);
        String placeholders = chunkIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(", "));
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        int deleted = jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE id IN (" + placeholders + ")", chunkIds.toArray());
        log.info("批量删除 chunk 向量，table={}, count={}, deleted={}", table, chunkIds.size(), deleted);
    }

    @Override
    public void updateChunk(String collectionName, String docId, VectorChunk chunk) {
        if (chunk.getEmbedding() == null) {
            throw new IllegalArgumentException("Chunk embedding 为空，无法更新");
        }
        int dimension = chunk.getEmbedding().length;
        String table = vectorTableName(dimension);
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.update(
                "INSERT INTO " + table + " (id, content, metadata, embedding, content_type) VALUES (?, ?, ?::jsonb, ?::vector, ?) " +
                        "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding, content_type = EXCLUDED.content_type",
                chunk.getChunkId(),
                chunk.getContent(),
                buildMetadataJson(collectionName, docId, chunk),
                toVectorLiteral(chunk.getEmbedding()),
                chunk.getContentType() != null ? chunk.getContentType() : "TEXT"
        );
    }

    private String buildMetadataJson(String collectionName, String docId, VectorChunk chunk) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (chunk.getMetadata() != null) {
            meta.putAll(chunk.getMetadata());
        }

        meta.put("collection_name", collectionName);
        meta.put("doc_id", docId);
        meta.put("chunk_index", chunk.getIndex());
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            throw new RuntimeException("元数据序列化失败", e);
        }
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            float v = embedding[i];
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                v = 0.0f;
            }
            sb.append(v);
        }
        return sb.append("]").toString();
    }

    private void autoCreateVectorTable(String collectionName, int dimension) {
        VectorSpaceSpec spec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder().logicalName(collectionName).build())
                .dimension(dimension)
                .build();
        vectorStoreAdmin.ensureVectorSpace(spec);
    }
}
