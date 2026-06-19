package com.byteq.ai.ragstudio.ingestion.controller.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 数据摄取管道节点视图对象
 */
@Data
public class IngestionPipelineNodeVO {

    /**
     * 主键 ID
     */
    private String id;

    /**
     * 节点的唯一标识符
     */
    private String nodeId;

    /**
     * 节点类型
     * 如 fetcher、parser、chunker、indexer 等
     */
    private String nodeType;

    /**
     * 节点的配置参数
     */
    private JsonNode settings;

    /**
     * 节点执行的条件表达式
     */
    private JsonNode condition;

    /**
     * 下一个节点ID，用于定义节点的执行顺序
     */
    private String nextNodeId;
}
