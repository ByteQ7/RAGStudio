package com.byteq.ai.ragstudio.rag.core.retrieve.audit;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.config.SearchAuditLogProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchAuditRecorder} / {@link SearchAudit} 单元测试
 */
class SearchAuditTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @TempDir
    Path tempDir;

    private SearchAuditRecorder newRecorder(boolean enabled) {
        SearchAuditLogProperties props = new SearchAuditLogProperties();
        props.setEnabled(enabled);
        props.setLogDir(tempDir.toString());
        return new SearchAuditRecorder(props);
    }

    private RetrievedChunk chunk(String id, String docName, String text, float score) {
        return RetrievedChunk.builder()
                .id(id)
                .docName(docName)
                .kbName("税务知识库")
                .text(text)
                .score(score)
                .build();
    }

    @Test
    void 未启用时begin返回null_零开销() {
        SearchAuditRecorder recorder = newRecorder(false);
        assertNull(recorder.begin());
    }

    @Test
    void finish输出合法JSON且关键字段齐全() throws IOException {
        SearchAuditRecorder recorder = newRecorder(true);
        SearchAudit audit = recorder.begin();
        assertNotNull(audit);

        // RRF 阶段：候选 c1(进入最终), c9(被截断丢弃)
        audit.addRrfCandidate("c1", "tax_kb_2024", 0.0163934f, 1);
        audit.addRrfCandidate("c9", "tax_kb_2024", 0.0147059f, 2);
        audit.markRerank("bge-reranker-v2-m3");

        List<RetrievedChunk> finalChunks = List.of(
                chunk("c1", "增值税申报指南.pdf", "2024年增值税申报逾期处理流程", 0.9231f));

        audit.finish("2024年增值税申报逾期怎么办", "用户原始问题", List.of("税务知识库"), 5, finalChunks);

        Path file = tempDir.resolve(LocalDate.now().format(DATE_FMT) + ".log");
        assertTrue(Files.exists(file), "应生成当日审计日志文件");

        JsonNode root = new ObjectMapper().readTree(Files.readString(file).trim());
        assertEquals(audit.getSearchId(), root.get("searchId").asText());
        assertEquals("2024年增值税申报逾期怎么办", root.get("query").asText());
        assertEquals("用户原始问题", root.get("userQuery").asText());
        assertEquals("税务知识库", root.get("kbNames").get(0).asText());
        assertEquals(5, root.get("topK").asInt());
        assertEquals("bge-reranker-v2-m3", root.get("rerankModel").asText());
        assertFalse(root.get("rerankFallback").asBoolean());
        assertEquals(1, root.get("finalCount").asInt());

        JsonNode chunkNode = root.get("chunks").get(0);
        assertEquals(1, chunkNode.get("order").asInt());
        assertEquals("c1", chunkNode.get("chunkId").asText());
        assertEquals("税务知识库", chunkNode.get("kbName").asText());
        assertEquals("增值税申报指南.pdf", chunkNode.get("docName").asText());
        assertEquals(0.0163934f, chunkNode.get("rrfScore").floatValue(), 1e-6);
        assertEquals(1, chunkNode.get("rrfRank").asInt());
        assertEquals("tax_kb_2024", chunkNode.get("rrfCollection").asText());
        assertTrue(chunkNode.get("rrfSurvived").asBoolean());
        assertEquals(0.9231f, chunkNode.get("finalScore").floatValue(), 1e-6);
        assertEquals("2024年增值税申报逾期处理流程", chunkNode.get("text").asText(), "默认应记录 chunk 正文");

        JsonNode candidates = root.get("candidates");
        assertEquals(1, candidates.size(), "被截断丢弃的候选应单独记录");
        JsonNode cand = candidates.get(0);
        assertEquals("c9", cand.get("chunkId").asText());
        assertFalse(cand.get("rrfSurvived").asBoolean());
    }

    @Test
    void rerank降级与正文开关() throws IOException {
        SearchAuditLogProperties props = new SearchAuditLogProperties();
        props.setEnabled(true);
        props.setIncludeChunkText(false);
        props.setRecordRrfCandidates(false);
        props.setLogDir(tempDir.toString());
        SearchAuditRecorder recorder = new SearchAuditRecorder(props);

        SearchAudit audit = recorder.begin();
        audit.markRerankFallback();
        List<RetrievedChunk> finalChunks = List.of(chunk("c1", "文档A.pdf", "正文内容", 0.5f));
        audit.finish("问题", null, List.of("库A"), 5, finalChunks);

        Path file = tempDir.resolve(LocalDate.now().format(DATE_FMT) + ".log");
        JsonNode root = new ObjectMapper().readTree(Files.readString(file).trim());
        assertTrue(root.get("rerankFallback").asBoolean());
        assertTrue(root.get("rerankModel").isNull(), "未指定重排模型时应为 null");
        assertNull(root.get("chunks").get(0).get("text"), "include-chunk-text=false 时不记录正文");
        assertFalse(root.has("candidates"), "未开启 record-rrf-candidates 时不输出候选");
    }
}
