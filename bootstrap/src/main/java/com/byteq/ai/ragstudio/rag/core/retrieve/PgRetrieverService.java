package com.byteq.ai.ragstudio.rag.core.retrieve;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgRetrieverService implements RetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        List<Float> embedding = embeddingService.embed(request.getQuery());
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding 服务返回空向量，无法执行检索。请检查 Embedding 模型配置和服务状态。");
        }
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        Integer dimension = request.getDimension();
        if (dimension == null || dimension <= 0) {
            dimension = vector.length;
        }
        String tableName = "t_knowledge_vector_" + dimension;

        jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 200");

        String vectorLiteral = toVectorLiteral(vector);
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query(
                "SELECT id, content, 1 - (embedding <=> ?::vector) AS score FROM " + tableName
                        + " WHERE metadata->>'collection_name' = ? ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, rowNum) -> RetrievedChunk.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .score(rs.getFloat("score"))
                        .build(),
                vectorLiteral, request.getCollectionName(), vectorLiteral, request.getTopK()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieveByKeyword(String query, RetrieveRequest request) {
        Integer dimension = request.getDimension();
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
            "SELECT id, content, similarity(content, ?) AS score FROM " + tableName +
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

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> RetrievedChunk.builder()
                    .id(rs.getString("id"))
                    .text(rs.getString("content"))
                    .score(rs.getFloat("score"))
                    .build(),
            params.toArray(new Object[0])
        );
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
}
