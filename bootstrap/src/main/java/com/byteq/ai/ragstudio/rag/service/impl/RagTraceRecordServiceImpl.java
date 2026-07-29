package com.byteq.ai.ragstudio.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.framework.trace.TraceStatus;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceRunDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.RagTraceNodeMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.RagTraceRunMapper;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG Trace 记录服务实现
 * <p>
 * 所有 DB 写入操作均通过 {@code traceRecordExecutor} 异步执行，
 * 避免同步 DB I/O 阻塞业务线程。trace 数据为纯观测用途，不影响业务逻辑，
 * 因此采用 fire-and-forget 模式：写入失败仅记录日志，不向调用方抛出异常。
 * </p>
 * <p>
 * 使用单线程执行器保证同一链路内的操作按提交顺序执行（startNode 先于 finishNode）。
 * 队列满时降级为 CallerRunsPolicy，最差情况退化为同步执行（等同改动前性能）。
 * </p>
 */
@Slf4j
@Service
public class RagTraceRecordServiceImpl implements RagTraceRecordService {

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;
    private final Executor traceRecordExecutor;

    private final AtomicLong writeFailureCount = new AtomicLong(0);

    public RagTraceRecordServiceImpl(RagTraceRunMapper runMapper,
                                     RagTraceNodeMapper nodeMapper,
                                     @Qualifier("traceRecordExecutor") Executor traceRecordExecutor) {
        this.runMapper = runMapper;
        this.nodeMapper = nodeMapper;
        this.traceRecordExecutor = traceRecordExecutor;
    }

    @Override
    public void startRun(RagTraceRunDO run) {
        execute("startRun", () -> runMapper.insert(run));
    }

    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        execute("finishRun", () -> {
            RagTraceRunDO update = RagTraceRunDO.builder()
                    .status(status)
                    .errorMessage(errorMessage)
                    .endTime(endTime)
                    .durationMs(durationMs)
                    .build();
            int rows = runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                    .eq(RagTraceRunDO::getTraceId, traceId));
            if (rows == 0) {
                log.warn("finishRun 未更新任何行，可能 startRun 尚未提交，traceId：{}", traceId);
            }
        });
    }

    @Override
    public void startNode(RagTraceNodeDO node) {
        execute("startNode", () -> nodeMapper.insert(node));
    }

    @Override
    public void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs) {
        execute("finishNode", () -> {
            RagTraceNodeDO update = RagTraceNodeDO.builder()
                    .status(status)
                    .errorMessage(errorMessage)
                    .endTime(endTime)
                    .durationMs(durationMs)
                    .build();
            int rows = nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                    .eq(RagTraceNodeDO::getTraceId, traceId)
                    .eq(RagTraceNodeDO::getNodeId, nodeId));
            if (rows == 0) {
                log.warn("finishNode 未更新任何行，open startNode 尚未提交，traceId：{}，nodeId：{}",
                        traceId, nodeId);
            }
        });
    }

    @Override
    public void deleteRun(String traceId) {
        try {
            nodeMapper.delete(Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                    .eq(RagTraceNodeDO::getTraceId, traceId));
            runMapper.delete(Wrappers.lambdaUpdate(RagTraceRunDO.class)
                    .eq(RagTraceRunDO::getTraceId, traceId));
            log.info("trace 已删除 traceId={}", traceId);
        } catch (Exception e) {
            log.error("trace 删除失败 traceId={}", traceId, e);
            throw e;
        }
    }

    @Override
    public int markStaleRunningAsError(int timeoutMinutes) {
        int marked = 0;
        try {
            Date cutoff = new Date(System.currentTimeMillis() - (long) timeoutMinutes * 60 * 1000);
            List<RagTraceRunDO> staleRuns = runMapper.selectList(
                    Wrappers.lambdaQuery(RagTraceRunDO.class)
                            .eq(RagTraceRunDO::getStatus, TraceStatus.RUNNING.name())
                            .le(RagTraceRunDO::getStartTime, cutoff)
            );
            for (RagTraceRunDO run : staleRuns) {
                RagTraceRunDO update = RagTraceRunDO.builder()
                        .status(TraceStatus.ERROR.name())
                        .errorMessage("[Auto-marked] Run timed out after " + timeoutMinutes + " minutes")
                        .endTime(new Date())
                        .durationMs(System.currentTimeMillis()
                                - (run.getStartTime() != null ? run.getStartTime().getTime() : 0))
                        .build();
                int rows = runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                        .eq(RagTraceRunDO::getTraceId, run.getTraceId())
                        .eq(RagTraceRunDO::getStatus, TraceStatus.RUNNING.name()));
                marked += rows;
            }
            if (marked > 0) {
                log.info("已标记 {} 条僵死 RUNNING 记录为 ERROR", marked);
            }
        } catch (Exception e) {
            log.error("标记僵死 RUNNING 记录时出错", e);
        }
        return marked;
    }

    private void execute(String operation, Runnable task) {
        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                long count = writeFailureCount.incrementAndGet();
                log.warn("trace {} 异步写入失败 (累计 {})", operation, count, e);
                if (count % 100 == 1) {
                    log.error("trace 异步写入失败累积 {} 次，请检查数据库和 trace 线程池状态", count);
                }
            }
        }, traceRecordExecutor);
    }
}
