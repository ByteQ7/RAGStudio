package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 系统性能指标 VO
 * <p>
 * 封装指定时间窗口内的系统性能数据，包括响应延迟、成功率、错误率、
 * 无知识回答率和慢查询率等指标，用于监控系统运行质量。
 * </p>
 */
@Data
@Builder
public class DashboardPerformanceVO {

    private String window;

    private Long avgLatencyMs;

    private Long p95LatencyMs;

    private Double successRate;

    private Double errorRate;

    private Double noDocRate;

    private Double slowRate;
}
