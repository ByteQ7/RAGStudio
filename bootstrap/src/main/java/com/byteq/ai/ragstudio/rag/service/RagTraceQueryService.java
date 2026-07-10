package com.byteq.ai.ragstudio.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.rag.controller.request.RagTraceRunPageRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceDetailVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceNodeVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceRunStatsVO;
import com.byteq.ai.ragstudio.rag.controller.vo.RagTraceRunVO;

import java.util.List;

/**
 * RAG 链路追踪查询服务接口
 * <p>
 * 提供 RAG 链路追踪记录的查询能力，包括分页查询运行记录、
 * 查询链路详情（含节点）以及仅查询链路节点等操作。
 * 用于运维监控和问题排查场景。
 * </p>
 */
public interface RagTraceQueryService {

    /**
     * 分页查询链路运行记录
     * <p>
     * 根据请求参数（含分页和过滤条件）查询链路运行记录列表。
     * </p>
     *
     * @param request 分页查询请求参数
     * @return 分页的链路运行记录视图对象列表
     */
    IPage<RagTraceRunVO> pageRuns(RagTraceRunPageRequest request);

    /**
     * 查询链路详情（包含节点）
     * <p>
     * 根据 traceId 查询完整的链路信息，包括运行记录和所有子节点。
     * </p>
     *
     * @param traceId 链路追踪 ID
     * @return 链路详情视图对象，包含运行记录和节点列表
     */
    RagTraceDetailVO detail(String traceId);

    /**
     * 仅查询链路节点
     * <p>
     * 根据 traceId 查询指定链路的所有节点信息。
     * </p>
     *
     * @param traceId 链路追踪 ID
     * @return 链路节点视图对象列表
     */
    List<RagTraceNodeVO> listNodes(String traceId);

    /**
     * 查询链路运行统计（全量数据）
     * <p>
     * 根据过滤条件查询所有匹配的链路运行记录，返回全量统计信息，
     * 包括成功率、平均耗时、P95 耗时等。不参与分页。
     * </p>
     *
     * @param request 过滤条件（traceId / conversationId / taskId / status）
     * @return 运行统计视图对象
     */
    RagTraceRunStatsVO stats(RagTraceRunPageRequest request);
}
