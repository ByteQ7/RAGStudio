package com.byteq.ai.ragstudio.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceRunDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.RagTraceNodeMapper;
import com.byteq.ai.ragstudio.rag.dao.mapper.RagTraceRunMapper;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
            runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                    .eq(RagTraceRunDO::getTraceId, traceId));
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
            nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                    .eq(RagTraceNodeDO::getTraceId, traceId)
                    .eq(RagTraceNodeDO::getNodeId, nodeId));
        });
    }

    /**
     * 异步执行 trace DB 操作
     * <p>
     * 使用 CompletableFuture.runAsync 提交到 traceRecordExecutor。
     * DB 操作异常被捕获并记录日志，不会传播到调用方。
     * 当执行器队列满时，CallerRunsPolicy 会降级为调用线程同步执行。
     * </p>
     *
     * @param operation 操作名称（用于日志标识）
     * @param task      DB 操作逻辑
     */
    private void execute(String operation, Runnable task) {
        CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("trace {} 异步写入失败", operation, e);
            }
        }, traceRecordExecutor);
    }
}
