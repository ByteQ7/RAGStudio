package com.byteq.ai.ragstudio.rag.core.vector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PgVectorStoreServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testChineseCharacterInsertion() {
        // 准备测试数据
        String chunkId = "test_chunk_001";
        Long kbId = 1L;
        String docId = "test_doc_001";
        Integer chunkIndex = 0;
        String content = "这是一段中文测试内容，包含各种字符：你好世界！";

        try {
            // 创建一个简单的向量（维度需与数据库 vector(1536) 列一致）
            float[] embedding = new float[1536];
            for (int i = 0; i < 1536; i++) {
                embedding[i] = 0.1f;
            }

            // 构建向量字符串
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(embedding[i]);
            }
            String vectorLiteral = sb.append("]").toString();

            // 插入数据
            String sql = "INSERT INTO t_knowledge_vector (chunk_id, kb_id, doc_id, chunk_index, content, embedding) " +
                         "VALUES (?, ?, ?, ?, ?, ?::vector)";

            int affectedRows = jdbcTemplate.update(sql, chunkId, kbId, docId, chunkIndex, content, vectorLiteral);
            assertEquals(1, affectedRows, "应该插入一行数据");

            // 查询验证
            String querySql = "SELECT chunk_id, content FROM t_knowledge_vector WHERE chunk_id = ?";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(querySql, chunkId);

            // 验证查询结果
            assertNotNull(results, "查询结果不应为null");
            assertEquals(1, results.size(), "应该查询到一条记录");

            Map<String, Object> row = results.get(0);
            assertNotNull(row, "记录不应为null");
            assertEquals(chunkId, row.get("chunk_id"), "chunk_id应该匹配");

            String retrievedContent = (String) row.get("content");
            assertNotNull(retrievedContent, "content不应为null");
            assertEquals(content, retrievedContent, "content内容应该完全匹配");
            assertEquals(content.length(), retrievedContent.length(), "content长度应该匹配");

        } finally {
            // 清理测试数据
            jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE chunk_id = ?", chunkId);

            // 验证清理成功
            String verifySql = "SELECT COUNT(*) FROM t_knowledge_vector WHERE chunk_id = ?";
            Long count = jdbcTemplate.queryForObject(verifySql, Long.class, chunkId);
            assertEquals(0L, count != null ? count : 0L, "测试数据应该被完全清理");
        }
    }
}
