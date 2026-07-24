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
        String chunkId = "test_chunk_001";
        String docId = "test_doc_001";
        Integer chunkIndex = 0;
        String content = "这是一段中文测试内容，包含各种字符：你好世界！";
        String metadata = "{\"collection_name\":\"test\",\"doc_id\":\"" + docId + "\",\"chunk_index\":" + chunkIndex + "}";

        try {
            float[] embedding = new float[1536];
            for (int i = 0; i < 1536; i++) {
                embedding[i] = 0.1f;
            }

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < embedding.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(embedding[i]);
            }
            String vectorLiteral = sb.append("]").toString();

            String sql = "INSERT INTO t_knowledge_vector_1536 (id, content, metadata, embedding) " +
                         "VALUES (?, ?, ?::jsonb, ?::vector)";
            int affectedRows = jdbcTemplate.update(sql, chunkId, content, metadata, vectorLiteral);
            assertEquals(1, affectedRows);

            String querySql = "SELECT id, content FROM t_knowledge_vector_1536 WHERE id = ?";
            List<Map<String, Object>> results = jdbcTemplate.queryForList(querySql, chunkId);

            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals(chunkId, results.get(0).get("id"));

            String retrievedContent = (String) results.get(0).get("content");
            assertNotNull(retrievedContent);
            assertEquals(content, retrievedContent);
            assertEquals(content.length(), retrievedContent.length());

        } finally {
            jdbcTemplate.update("DELETE FROM t_knowledge_vector_1536 WHERE id = ?", chunkId);

            String verifySql = "SELECT COUNT(*) FROM t_knowledge_vector_1536 WHERE id = ?";
            Long count = jdbcTemplate.queryForObject(verifySql, Long.class, chunkId);
            assertEquals(0L, count != null ? count : 0L);
        }
    }
}
