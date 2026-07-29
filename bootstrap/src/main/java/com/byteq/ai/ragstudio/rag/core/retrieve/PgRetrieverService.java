package com.byteq.ai.ragstudio.rag.core.retrieve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgRetrieverService implements RetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        String embeddingModel = resolveEmbeddingModelFromCollection(request.getCollectionName());
        Integer kbDimension = resolveDimensionFromCollection(request.getCollectionName());
        List<Float> embedding;
        if (embeddingModel != null) {
            embedding = embeddingService.embed(request.getQuery(), embeddingModel, kbDimension);
        } else {
            embedding = embeddingService.embed(request.getQuery());
        }
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding 服务返回空向量，无法执行检索。请检查 Embedding 模型配置和服务状态。");
        }
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        Integer dimension = request.getDimension();
        if (dimension == null || dimension <= 0) {
            dimension = resolveDimensionFromCollection(request.getCollectionName());
        }
        if (dimension == null || dimension <= 0) {
            dimension = vector.length;
        }

        // 查询向量维度与表维度不一致时，SQL 会失败并污染连接。提前返回空列表。
        if (dimension != vector.length) {
            log.warn("查询向量维度({})与KB维度({})不一致，跳过collection={}的向量检索",
                    vector.length, dimension, request.getCollectionName());
            return List.of();
        }

        String tableName = "t_knowledge_vector_" + dimension;
        try {
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 200");

            String vectorLiteral = toVectorLiteral(vector);
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            return jdbcTemplate.query(
                    "SELECT id, content, metadata, " +
                            "COALESCE(NULLIF(content_type, 'TEXT'), metadata->>'content_type', 'TEXT') AS content_type, " +
                            "1 - (embedding <=> ?::vector) AS score FROM " + tableName
                            + " WHERE metadata->>'collection_name' = ? ORDER BY embedding <=> ?::vector LIMIT ?",
                    (rs, rowNum) -> buildChunk(rs),
                    vectorLiteral, request.getCollectionName(), vectorLiteral, request.getTopK()
            );
        } catch (Exception e) {
            log.warn("向量检索SQL失败: collection={}, dim={}, error={}",
                    request.getCollectionName(), dimension, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RetrievedChunk> retrieveByKeyword(String query, RetrieveRequest request) {
        Integer dimension = request.getDimension();
        if (dimension == null || dimension <= 0) {
            dimension = resolveDimensionFromCollection(request.getCollectionName());
        }
        if (dimension == null || dimension <= 0) {
            dimension = 1536;
        }
        String tableName = "t_knowledge_vector_" + dimension;

        String[] words = query.split("[\\s,，。.；;：:！!？?]+");
        java.util.List<String> meaningful = java.util.Arrays.stream(words)
                .filter(w -> !w.isBlank() && w.length() >= 2)
                .toList();

        if (meaningful.isEmpty()) {
            meaningful = java.util.List.of(query);
        }

        StringBuilder sql = new StringBuilder(
            "SELECT id, content, metadata, " +
            "COALESCE(NULLIF(content_type, 'TEXT'), metadata->>'content_type', 'TEXT') AS content_type, " +
            "similarity(content, ?) AS score FROM " + tableName +
            " WHERE metadata->>'collection_name' = ? AND ("
        );
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add(query);
        params.add(request.getCollectionName());

        for (int i = 0; i < meaningful.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("content ILIKE ?");
            params.add("%" + meaningful.get(i) + "%");
        }
        sql.append(") ORDER BY score DESC LIMIT ?");
        params.add(request.getTopK());

        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> buildChunk(rs),
                params.toArray(new Object[0])
            );
        } catch (Exception e) {
            log.warn("关键词检索SQL失败: collection={}, dim={}, error={}",
                    request.getCollectionName(), dimension, e.getMessage());
            return List.of();
        }
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }

    private RetrievedChunk buildChunk(java.sql.ResultSet rs) throws java.sql.SQLException {
        String metadataJson = rs.getString("metadata");
        Map<String, Object> metadata = Map.of();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.debug("解析 metadata JSON 失败: chunkId={}", rs.getString("id"));
            }
        }
        String contentType = rs.getString("content_type");
        if (contentType == null || contentType.isBlank()) contentType = "TEXT";
        return RetrievedChunk.builder()
                .id(rs.getString("id"))
                .text(rs.getString("content"))
                .score(rs.getFloat("score"))
                .metadata(metadata)
                .contentType(contentType)
                .build();
    }

    private Integer resolveDimensionFromCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT dimension FROM t_knowledge_base WHERE collection_name = ? LIMIT 1",
                    Integer.class, collectionName);
        } catch (Exception e) {
            log.debug("通过 collectionName 查询 KB 维度失败: {}", collectionName);
            return null;
        }
    }

    private String resolveEmbeddingModelFromCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT embedding_model FROM t_knowledge_base WHERE collection_name = ? LIMIT 1",
                    String.class, collectionName);
        } catch (Exception e) {
            log.debug("通过 collectionName 查询 KB embedding 模型失败: {}", collectionName);
            return null;
        }
    }
}
