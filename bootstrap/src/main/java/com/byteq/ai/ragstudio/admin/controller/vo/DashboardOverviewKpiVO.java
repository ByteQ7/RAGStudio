package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 仪表盘 KPI 指标值 VO
 * <p>
 * 封装单个指标的数值、变化量和变化百分比，用于展示指标的趋势方向。
 * 正值表示增长，负值表示下降。
 * </p>
 */
@Data
@Builder
public class DashboardOverviewKpiVO {

    private Long value;

    private Long delta;

    private Double deltaPct;
}
