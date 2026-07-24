package com.byteq.ai.ragstudio.rag.core.vector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 pgvector 的向量空间管理实现
 * <p>
 * 根据 VectorSpaceSpec.dimension 确定目标表（t_knowledge_vector_{dimension}），
 * 确保表存在并创建 HNSW 索引。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreAdmin implements VectorStoreAdmin {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        int dimension = spec.getDimension();
        String tableName = "t_knowledge_vector_" + dimension;
        String indexName = "idx_kv_" + dimension + "_embedding";

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);

        if (count != null && count > 0) {
            log.debug("HNSW索引已存在: {}", indexName);
            return;
        }

        log.info("创建/确认向量表 {}，维度: {}", tableName, dimension);
        jdbcTemplate.execute(String.format(
                "CREATE TABLE IF NOT EXISTS %s (id VARCHAR(64) PRIMARY KEY, content TEXT, metadata JSONB, embedding vector(%d))",
                tableName, dimension));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS %s ON %s USING hnsw (embedding vector_cosine_ops)",
                indexName, tableName));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS %s_metadata ON %s USING gin(metadata)", tableName, tableName));
        jdbcTemplate.execute(String.format(
                "CREATE INDEX IF NOT EXISTS %s_content_trgm ON %s USING gin (content gin_trgm_ops)", tableName, tableName));
    }

    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_tables WHERE tablename LIKE 't_knowledge_vector_%'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
