package com.byteq.ai.ragstudio.rag.schedule;

import com.byteq.ai.ragstudio.rag.config.RagTraceProperties;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 链路追踪僵死记录定时清理任务
 * <p>
 * 每分钟扫描一次，将超过配置时长的 RUNNING 状态运行记录标记为 ERROR。
 * 用于处理因服务重启、线程异常或 DB 写入失败导致的状态未更新问题。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagTraceCleanupScheduleJob {

    private final RagTraceRecordService traceRecordService;
    private final RagTraceProperties traceProperties;

    /**
     * 定时标记僵死 RUNNING 记录为 ERROR
     * <p>
     * 固定延迟 60 秒，初始延迟 60 秒后开始执行。
     * 若配置的 staleRunTimeoutMinutes 为 0 则跳过清理。
     * </p>
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void cleanStaleRuns() {
        int timeoutMinutes = traceProperties.getStaleRunTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            return;
        }
        try {
            int marked = traceRecordService.markStaleRunningAsError(timeoutMinutes);
            if (marked > 0) {
                log.info("TraceCleanup: 标记 {} 条僵死记录为 ERROR (threshold={}min)", marked, timeoutMinutes);
            }
        } catch (Exception e) {
            log.error("TraceCleanup 执行失败", e);
        }
    }
}
