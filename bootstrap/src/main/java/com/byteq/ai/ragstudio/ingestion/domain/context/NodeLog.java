package com.byteq.ai.ragstudio.ingestion.domain.context;

import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流水线节点日志实体类
 * <p>
 * 记录摄入流水线中各个节点的执行信息，包括执行耗时、状态、输出等。
 * 由 {@link IngestionEngine} 在每个节点
 * 执行完成后自动生成，用于流水线执行过程的监控和问题排查。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeLog {

    /**
     * 节点的唯一标识符
     * 对应 {@link NodeConfig#getNodeId()}
     */
    private String nodeId;

    /**
     * 节点类型
     * 如 "fetcher"、"parser"、"chunker" 等，对应 {@link IngestionNodeType}
     */
    private String nodeType;

    /**
     * 节点执行的日志消息
     * 包含节点处理的概要信息，如处理的数据量、操作结果等
     */
    private String message;

    /**
     * 节点执行耗时（毫秒）
     * 记录节点从开始执行到完成所消耗的时间
     */
    private long durationMs;

    /**
     * 节点是否执行成功
     * true 表示执行成功或跳过，false 表示执行失败
     */
    private boolean success;

    /**
     * 节点执行失败时的错误信息
     * 成功时为 null
     */
    private String error;

    /**
     * 节点的输出数据
     * 存储节点处理后产生的结构化输出数据，用于追踪节点的处理结果
     */
    private java.util.Map<String, Object> output;
}
