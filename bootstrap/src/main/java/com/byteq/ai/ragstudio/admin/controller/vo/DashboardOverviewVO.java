package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 仪表盘概览数据 VO
 * <p>
 * 封装系统运行的核心 KPI 概览数据，包含时间窗口信息和各项指标的分组数据。
 * 用于管理后台首页的数据大屏展示。
 * </p>
 */
@Data
@Builder
public class DashboardOverviewVO {

    private String window;

    private String compareWindow;

    private Long updatedAt;

    private DashboardOverviewGroupVO kpis;
}
