package com.byteq.ai.ragstudio.knowledge.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 定时调度执行状态枚举
 * <p>
 * 表示文档定时同步任务每次执行的生命周期状态。
 * 状态流转：RUNNING（运行中）→ SUCCESS（成功）/ FAILED（失败）/ SKIPP（跳过）
 */
@Getter
@RequiredArgsConstructor
public enum ScheduleRunStatus {

    /**
     * 运行中：定时任务正在执行远程文件拉取、分块和向量化操作
     */
    RUNNING("running"),

    /**
     * 执行成功：定时任务完成，文档已成功同步和向量化
     */
    SUCCESS("success"),

    /**
     * 执行失败：定时任务执行过程中发生错误
     */
    FAILED("failed"),

    /**
     * 已跳过：远程文件未发生变化或文档正在处理中，跳过本次执行
     */
    SKIPPED("skipped");

    /**
     * 状态码（对应数据库中的存储值）
     */
    private final String code;
}
