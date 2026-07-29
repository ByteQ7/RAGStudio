package com.byteq.ai.ragstudio.rag.core.vector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

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

        // 确保 content_type 列存在（兼容旧表）
        try {
            jdbcTemplate.execute(String.format(
                    "ALTER TABLE %s ADD COLUMN IF NOT EXISTS content_type VARCHAR(16) DEFAULT 'TEXT'",
                    tableName));
        } catch (Exception e) {
            log.warn("添加 content_type 列失败（表可能尚未创建）: {}", e.getMessage());
        }

        // 用表存在性判断（而非索引），>2000 维无法建 HNSW 索引但表仍可存在
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_tables WHERE tablename = ?", Integer.class, tableName);
        if (count != null && count > 0) {
            log.debug("向量表已存在: {}", tableName);
            return;
        }

        log.info("创建向量表 {}，维度: {}", tableName, dimension);
        jdbcTemplate.execute(String.format(
                "CREATE TABLE IF NOT EXISTS %s (id VARCHAR(64) PRIMARY KEY, content TEXT, metadata JSONB, embedding vector(%d))",
                tableName, dimension));

        // HNSW 索引仅支持 ≤2000 维度，超过则跳过（检索时退化为顺序扫描）
        if (dimension <= 2000) {
            jdbcTemplate.execute(String.format(
                    "CREATE INDEX IF NOT EXISTS %s ON %s USING hnsw (embedding vector_cosine_ops)",
                    indexName, tableName));
        } else {
            log.warn("向量维度 {} > 2000，跳过 HNSW 索引创建（pgvector 限制），向量检索将使用顺序扫描", dimension);
        }

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

    @Override
    public void deleteCollectionVectors(String collectionName) {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE tablename LIKE 't_knowledge_vector_%'",
                String.class);
        if (tables.isEmpty()) {
            log.info("无向量表需要清理, collectionName={}", collectionName);
            return;
        }
        for (String table : tables) {
            try {
                int deleted = jdbcTemplate.update(
                        "DELETE FROM " + table + " WHERE metadata->>'collection_name' = ?",
                        collectionName);
                if (deleted > 0) {
                    log.info("清理 {} 表 {} 条向量记录, collectionName={}", table, deleted, collectionName);
                }
            } catch (Exception e) {
                log.warn("清理向量表 {} 失败: {}", table, e.getMessage());
            }
        }
    }
}
