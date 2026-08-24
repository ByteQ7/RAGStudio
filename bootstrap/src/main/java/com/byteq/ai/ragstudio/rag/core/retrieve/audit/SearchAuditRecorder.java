package com.byteq.ai.ragstudio.rag.core.retrieve.audit;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.rag.config.SearchAuditLogProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索审计日志记录器
 * <p>
 * 记录每次 RAG 检索的 Chunk 所属文档名、RRF 分数、重排后顺序。
 * 默认关闭（{@code rag.search.audit-log.enabled=false}），关闭时 {@link #begin()} 返回 null，零开销。
 * 开启时每次检索输出一行 JSON 到 {@code {log-dir}/YYYYMMDD.log}（按日归档，仿 {@code AiLogService}）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAuditRecorder {

    private final SearchAuditLogProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @PostConstruct
    public void init() {
        if (properties.isEnabled()) {
            log.info("RAG 检索审计日志已启用，保存路径: {}", Paths.get(properties.getLogDir()).toAbsolutePath());
        }
    }

    /**
     * 开始一次检索的审计；未启用时返回 null（所有采集点判空短路）
     */
    public SearchAudit begin() {
        if (!properties.isEnabled()) {
            return null;
        }
        return new SearchAudit(this);
    }

    boolean isIncludeChunkText() {
        return properties.isIncludeChunkText();
    }

    /**
     * 输出一次检索的完整审计记录（一行 JSON，追加到当日文件）
     */
    void write(SearchAudit audit, String query, String userQuery, List<String> kbNames,
               int topK, List<RetrievedChunk> chunks) {
        try {
            java.util.Set<String> finalIds = SearchAudit.idSet(chunks);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("searchId", audit.getSearchId());
            root.put("time", audit.getTime());
            root.put("latencyMs", audit.getLatencyMs());
            root.put("query", query);
            root.put("userQuery", userQuery);
            root.put("kbNames", SearchAudit.dedup(kbNames));
            root.put("topK", topK);
            root.put("rerankModel", audit.getRerankModel());
            root.put("rerankFallback", audit.isRerankFallback());
            root.put("finalCount", chunks.size());
            root.put("chunks", audit.buildChunkList(chunks, finalIds));
            if (properties.isRecordRrfCandidates()) {
                root.put("candidates", audit.buildCandidateList(finalIds));
            }
            writeLine(objectMapper.writeValueAsString(root));
        } catch (Exception e) {
            log.warn("写入 RAG 检索审计日志失败: searchId={}", audit.getSearchId(), e);
        }
    }

    private void writeLine(String line) {
        try {
            Path dir = Paths.get(properties.getLogDir());
            Files.createDirectories(dir);
            Path file = dir.resolve(LocalDate.now().format(DATE_FMT) + ".log");
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写入 RAG 检索审计日志文件失败: {}", e.getMessage());
        }
    }
}
