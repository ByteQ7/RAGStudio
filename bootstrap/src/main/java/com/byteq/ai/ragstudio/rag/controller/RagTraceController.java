package com.byteq.ai.ragstudio.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.controller.request.RagTraceRunPageRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceDetailVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceNodeVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceRunStatsVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceRunVO;
import com.byteq.ai.ragstudio.rag.service.RagTraceQueryService;
import com.byteq.ai.ragstudio.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 链路追踪查询控制器
 * <p>
 * 提供 RAG 链路追踪（Trace）记录的查询接口，包括分页查询运行记录、
 * 查询链路详情以及查询链路节点信息等。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class RagTraceController {

    /**
     * RAG 链路追踪查询服务
     */
    private final RagTraceQueryService ragTraceQueryService;

    /**
     * RAG 链路追踪记录服务（写入/删除）
     */
    private final RagTraceRecordService ragTraceRecordService;

    /**
     * 分页查询链路运行记录
     * <p>
     * 支持按条件分页查询 RAG 对话的链路追踪运行记录，用于运维和调试场景。
     * </p>
     *
     * @param request 分页查询请求参数，包含分页信息和过滤条件
     * @return 分页的链路运行记录列表
     */
    @GetMapping("/rag/traces/runs")
    public Result<IPage<RagTraceRunVO>> pageRuns(RagTraceRunPageRequest request) {
        return Results.success(ragTraceQueryService.pageRuns(request));
    }

    /**
     * 查询链路详情（包含节点）
     * <p>
     * 根据 traceId 查询完整的链路详细信息，包括运行记录和所有子节点。
     * </p>
     *
     * @param traceId 链路追踪 ID
     * @return 链路详情，包含运行记录和节点列表
     */
    @GetMapping("/rag/traces/runs/{traceId}")
    public Result<RagTraceDetailVO> detail(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.detail(traceId));
    }

    /**
     * 仅查询链路节点
     * <p>
     * 根据 traceId 查询指定链路的所有节点信息，不含运行记录详情。
     * </p>
     *
     * @param traceId 链路追踪 ID
     * @return 链路节点列表
     */
    @GetMapping("/rag/traces/runs/{traceId}/nodes")
    public Result<List<RagTraceNodeVO>> nodes(@PathVariable String traceId) {
        return Results.success(ragTraceQueryService.listNodes(traceId));
    }

    /**
     * 查询链路运行统计（全量数据）
     * <p>
     * 基于全量运行记录计算统计指标，不参与分页。
     * 包括总记录数、成功/失败数、成功率、平均耗时、P95 耗时。
     * </p>
     *
     * @param request 过滤条件（与分页查询一致）
     * @return 运行统计信息
     */
    @GetMapping("/rag/traces/runs/stats")
    public Result<RagTraceRunStatsVO> stats(RagTraceRunPageRequest request) {
        return Results.success(ragTraceQueryService.stats(request));
    }

    /**
     * 删除一条链路运行记录及其所有节点
     * <p>
     * 同步删除指定 traceId 的运行记录和关联节点。
     * 用于手动清理 stuck RUNNING 或不需要的 trace 数据。
     * </p>
     *
     * @param traceId 链路追踪 ID
     * @return 操作结果
     */
    @DeleteMapping("/rag/traces/runs/{traceId}")
    public Result<Void> delete(@PathVariable String traceId) {
        ragTraceRecordService.deleteRun(traceId);
        return Results.success();
    }
}
