package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * RAG 链路追踪运行记录视图对象
 * <p>
 * 表示一次完整的 RAG 对话链路追踪运行记录，
 * 包含入口方法、会话信息、运行状态和耗时等关键数据。
 * </p>
 */
@Data
@Builder
public class RagTraceRunVO {

    /** 链路追踪 ID */
    private String traceId;

    /** 链路名称 */
    private String traceName;

    /** 触发入口方法，格式为 "类名#方法名" */
    private String entryMethod;

    /** 关联的会话 ID */
    private String conversationId;

    /** 关联的任务 ID */
    private String taskId;

    /** 用户 ID */
    private String userId;

    /** 用户名称 */
    private String username;

    /** 运行状态（RUNNING / SUCCESS / ERROR） */
    private String status;

    /** 错误信息（状态为 ERROR 时提供） */
    private String errorMessage;

    /** 总耗时（毫秒） */
    private Long durationMs;

    /** 开始时间 */
    private Date startTime;

    /** 结束时间 */
    private Date endTime;
}
