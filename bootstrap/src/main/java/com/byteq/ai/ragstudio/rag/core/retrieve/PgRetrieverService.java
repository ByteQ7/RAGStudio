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

    /**
     * 根据自然语言查询执行向量检索
     * <p>
     * 流程：调用 Embedding 服务获取向量 -> L2 归一化 -> 按向量相似度检索
     *
     * @param request 检索请求，包含查询文本、topK、collectionName 等
     * @return 按相似度排序的 Chunk 列表
     */
    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        List<Float> embedding = embeddingService.embed(request.getQuery());
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding 服务返回空向量，无法执行检索。请检查 Embedding 模型配置和服务状态。");
        }
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    /**
     * 根据向量直接在 pgvector 中执行余弦相似度检索
     * <p>
     * 使用 HNSW 索引，通过 SET LOCAL 设置 ef_search=200 以平衡精度和性能
     *
     * @param vector  归一化后的查询向量
     * @param request 检索请求，包含 collectionName 和 topK
     * @return 按相似度排序的 Chunk 列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        // 使用 SET LOCAL 限定作用域为当前事务，避免连接池复用时污染其他查询
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 200");

        String vectorLiteral = toVectorLiteral(vector);
        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query("SELECT id, content, 1 - (embedding <=> ?::vector) AS score FROM t_knowledge_vector WHERE metadata->>'collection_name' = ? ORDER BY embedding <=> ?::vector LIMIT ?",
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
        // PostgreSQL 默认分词器不拆分中文词（无空格），tsvector/tsquery 无法命中中文关键词。
        // 改用 ILIKE 子串匹配：将 query 按标点拆成多词，所有词都必须匹配。
        String[] words = query.split("[\\s,，。.；;：:！!？?]+");
        java.util.List<String> meaningful = java.util.Arrays.stream(words)
                .filter(w -> !w.isBlank() && w.length() >= 2)
                .toList();

        if (meaningful.isEmpty()) {
            meaningful = java.util.List.of(query);
        }

        // 构建 ILIKE AND 条件
        StringBuilder sql = new StringBuilder(
            "SELECT id, content, 0.5 AS score FROM t_knowledge_vector " +
            "WHERE metadata->>'collection_name' = ? "
        );
        java.util.List<String> params = new java.util.ArrayList<>();
        params.add(request.getCollectionName());

        for (String w : meaningful) {
            sql.append(" AND content ILIKE ?");
            params.add("%" + w + "%");
        }
        sql.append(" LIMIT ?");
        params.add(String.valueOf(request.getTopK()));

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        return jdbcTemplate.query(
            sql.toString(),
            (rs, rowNum) -> RetrievedChunk.builder()
                    .id(rs.getString("id"))
                    .text(rs.getString("content"))
                    .score(rs.getFloat("score"))
                    .build(),
            params.toArray()
        );
    }

    // 对向量进行 L2 归一化，使其模长为 1
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

    // 将 Float 列表转换为 float 原始类型数组
    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    // 将 float 数组转换为 pgvector 可识别的字面量格式（如 [0.1,0.2,...]）
    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }
}
