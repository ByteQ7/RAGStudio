package com.byteq.ai.ragstudio.ingestion.controller.request;

import com.byteq.ai.ragstudio.ingestion.domain.graph.IngestionGraph;
import lombok.Data;

import java.util.List;

/**
 * 摄取管道创建请求对象
 * 用于接收创建新摄取管道的请求参数，包括管道名称、描述及节点配置列表
 */
@Data
public class IngestionPipelineCreateRequest {

    /**
     * 管道名称
     */
    private String name;

    /**
     * 管道描述信息
     */
    private String description;

    /**
     * 管道节点配置列表（线性接口；提供 graph 时忽略）
     */
    private List<IngestionPipelineNodeRequest> nodes;

    /**
     * 画布图（可空）；提供时服务端校验并编译为节点配置，图与节点一并落库
     */
    private IngestionGraph graph;
}
