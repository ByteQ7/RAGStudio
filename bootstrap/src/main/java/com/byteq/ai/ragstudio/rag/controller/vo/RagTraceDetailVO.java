package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RAG 链路追踪详情视图对象
 * <p>
 * 包含链路运行记录及其所有节点信息的聚合视图，
 * 用于在前端展示完整的链路追踪详情。
 * </p>
 */
@Data
@Builder
public class RagTraceDetailVO {

    /** 链路运行记录 */
    private RagTraceRunVO run;

    /** 链路节点列表 */
    private List<RagTraceNodeVO> nodes;
}
