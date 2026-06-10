package com.byteq.ai.ragstudio.knowledge.schedule;

import lombok.Builder;
import lombok.Getter;

import java.util.Date;

/**
 * 调度状态上下文
 * <p>记录定时调度任务在执行过程中的状态信息，用于在调度执行的不同阶段
 * 传递和更新调度记录的执行结果和下次执行计划。</p>
 */
@Getter
@Builder
public class ScheduleStateContext {

    /**
     * 调度任务 ID
     */
    private final String scheduleId;

    /**
     * 本次执行记录 ID（t_knowledge_document_schedule_exec 表主键）
     */
    private final String execId;

    /**
     * Cron 表达式
     */
    private final String cronExpr;

    /**
     * 本次执行开始时间
     */
    private final Date startTime;

    /**
     * 计算得出的下次执行时间
     */
    private final Date nextRunTime;
}
