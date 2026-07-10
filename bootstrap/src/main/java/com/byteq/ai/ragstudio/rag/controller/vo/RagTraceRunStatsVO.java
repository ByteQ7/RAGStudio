package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG Trace 运行记录统计视图对象
 * <p>基于全量数据的汇总统计，包含成功率、平均耗时、P95 耗时等。 </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagTraceRunStatsVO {

    /** 总记录数 */
    private long totalCount;

    /** 成功数 */
    private long successCount;

    /** 失败数 */
    private long failCount;

    /** 成功率（百分比，如 95.5） */
    private double successRate;

    /** 平均耗时（毫秒） */
    private long avgDurationMs;

    /** P95 耗时（毫秒） */
    private long p95DurationMs;
}
