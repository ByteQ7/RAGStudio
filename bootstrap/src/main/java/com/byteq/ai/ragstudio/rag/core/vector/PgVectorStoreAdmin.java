package com.byteq.ai.ragstudio.rag.core.vector;

import com.byteq.ai.ragstudio.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 pgvector 的向量空间管理实现
 * 负责在 PostgreSQL 中创建和管理 HNSW 索引，确保向量检索所需的索引结构存在
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgVectorStoreAdmin implements VectorStoreAdmin {


    private final JdbcTemplate jdbcTemplate;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 确保 HNSW 向量索引存在（幂等操作）
     * <p>
     * 检查 pg_indexes 中是否已有索引，不存在则创建基于余弦距离的 HNSW 索引
     *
     * @param spec 向量空间规格
     */
    @Override
    public void ensureVectorSpace(VectorSpaceSpec spec) {
        String indexName = "idx_kv_embedding_hnsw";

        // noinspection SqlDialectInspection,SqlNoDataSourceInspection
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?", Integer.class, indexName);

        if (count != null && count > 0) {
            log.debug("HNSW索引已存在: {}", indexName);
            return;
        }

        int dimension = ragDefaultProperties.getDimension();
        log.info("创建pgvector HNSW索引，维度: {}", dimension);
        jdbcTemplate.execute(String.format("CREATE INDEX IF NOT EXISTS %s ON t_knowledge_vector USING hnsw (embedding vector_cosine_ops)", indexName));
    }

    /**
     * 判断向量空间是否存在（通过查询 t_knowledge_vector 表判断）
     *
     * @param spaceId 向量空间标识
     * @return true 表示表存在且可访问，false 表示不存在或查询异常
     */
    @Override
    public boolean vectorSpaceExists(VectorSpaceId spaceId) {
        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_knowledge_vector LIMIT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
