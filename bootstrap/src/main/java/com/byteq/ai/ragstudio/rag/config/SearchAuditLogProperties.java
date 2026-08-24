package com.byteq.ai.ragstudio.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索审计日志配置
 * <p>
 * 记录每次 RAG 检索的 Chunk 所属文档名、RRF 分数、重排后顺序，输出到独立文件。
 * 默认关闭，关闭时零开销。
 * </p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.search.audit-log")
public class SearchAuditLogProperties {
    /** 是否启用 RAG 检索审计日志 */
    private boolean enabled = false;

    /** 是否记录 Chunk 正文 */
    private boolean includeChunkText = true;

    /** 是否记录 RRF 全量候选（含被截断丢弃的） */
    private boolean recordRrfCandidates = true;

    /** 审计日志输出目录，按日归档：{log-dir}/YYYYMMDD.log */
    private String logDir = "./logs/rag-search";
}
