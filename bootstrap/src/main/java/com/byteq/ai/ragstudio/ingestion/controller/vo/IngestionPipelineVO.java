package com.byteq.ai.ragstudio.ingestion.controller.vo;

import com.byteq.ai.ragstudio.ingestion.domain.graph.IngestionGraph;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 数据摄取管道视图对象
 */
@Data
public class IngestionPipelineVO {

    /**
     * 管道ID
     */
    private String id;

    /**
     * 管道名称
     */
    private String name;

    /**
     * 管道描述
     */
    private String description;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 管道节点列表
     */
    private List<IngestionPipelineNodeVO> nodes;

    /**
     * 画布图（graph_json 为空时由节点列表自动生成，保证可直接画布编辑）
     */
    private IngestionGraph graph;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}
