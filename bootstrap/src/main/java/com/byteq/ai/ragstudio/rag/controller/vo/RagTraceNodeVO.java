package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * RAG 链路追踪节点视图对象
 * <p>
 * 表示链路追踪中的一个节点，对应 RAG 对话流程中的某个步骤
 * （如查询改写、意图解析、知识检索、LLM 调用等）。
 * </p>
 */
@Data
@Builder
public class RagTraceNodeVO {

    /** 所属链路追踪 ID */
    private String traceId;

    /** 节点 ID（全局唯一） */
    private String nodeId;

    /** 父节点 ID，用于构建节点树形结构 */
    private String parentNodeId;

    /** 节点深度（根节点为 0） */
    private Integer depth;

    /** 节点类型（REWRITE / RETRIEVE / LLM 等） */
    private String nodeType;

    /** 节点名称，用于前端展示 */
    private String nodeName;

    /** 类名（AOP 自动采集时记录） */
    private String className;

    /** 方法名（AOP 自动采集时记录） */
    private String methodName;

    /** 节点状态（RUNNING / SUCCESS / ERROR） */
    private String status;

    /** 错误信息（状态为 ERROR 时提供） */
    private String errorMessage;

    /** 节点耗时（毫秒） */
    private Long durationMs;

    /** 开始时间 */
    private Date startTime;

    /** 结束时间 */
    private Date endTime;
}
