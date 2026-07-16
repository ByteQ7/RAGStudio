package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceNodeDO;
import com.byteq.ai.ragstudio.rag.dao.entity.RagTraceRunDO;

import java.util.Date;

/**
 * RAG 链路追踪记录服务接口
 * <p>
 * 负责 RAG 链路追踪（Trace）的运行记录和节点记录的持久化操作。
 * 提供启动/完成运行记录和启动/完成节点记录的写入能力，
 * 用于全链路监控和问题排查。
 * </p>
 */
public interface RagTraceRecordService {

    /**
     * 开始一条链路运行记录
     * <p>
     * 在链路追踪开始时调用，将运行的基本信息（如 traceId、入口方法、用户等）持久化到数据库。
     * </p>
     *
     * @param run 链路运行记录实体，包含 traceId、traceName、entryMethod 等信息
     */
    void startRun(RagTraceRunDO run);

    /**
     * 完成一条链路运行记录
     * <p>
     * 在链路追踪结束时调用，更新运行记录的结束时间、状态和耗时。
     * </p>
     *
     * @param traceId      链路追踪 ID
     * @param status       运行状态（SUCCESS / ERROR）
     * @param errorMessage 错误信息（状态为 ERROR 时提供）
     * @param endTime      结束时间
     * @param durationMs   总耗时（毫秒）
     */
    void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 开始一个链路节点记录
     * <p>
     * 在链路中的某个步骤（如改写、检索、LLM 调用等）开始时调用，记录节点的基本信息。
     * </p>
     *
     * @param node 链路节点记录实体，包含 nodeId、nodeType、nodeName、parentNodeId 等信息
     */
    void startNode(RagTraceNodeDO node);

    /**
     * 完成一个链路节点记录
     * <p>
     * 在链路中的某个步骤结束时调用，更新节点的结束时间、状态和耗时。
     * </p>
     *
     * @param traceId      链路追踪 ID
     * @param nodeId       节点 ID
     * @param status       节点状态（SUCCESS / ERROR）
     * @param errorMessage 错误信息（状态为 ERROR 时提供）
     * @param endTime      结束时间
     * @param durationMs   节点耗时（毫秒）
     */
    void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs);

    /**
     * 删除一条链路运行记录及其所有节点
     * <p>
     * 同步执行删除操作，先删所有关联节点，再删运行记录本身。
     * 用于手动清理 stuck RUNNING 或不需要的 trace 数据。
     * </p>
     *
     * @param traceId 链路追踪 ID
     */
    void deleteRun(String traceId);
}
