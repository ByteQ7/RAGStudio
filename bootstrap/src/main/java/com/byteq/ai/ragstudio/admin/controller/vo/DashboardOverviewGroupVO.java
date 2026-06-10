package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 仪表盘 KPI 指标分组 VO
 * <p>
 * 将所有核心 KPI 指标按业务含义分组，包括用户指标、会话指标和消息指标。
 * 每个指标包含数值和变化趋势。
 * </p>
 */
@Data
@Builder
public class DashboardOverviewGroupVO {

    private DashboardOverviewKpiVO totalUsers;

    private DashboardOverviewKpiVO activeUsers;

    private DashboardOverviewKpiVO totalSessions;

    private DashboardOverviewKpiVO sessions24h;

    private DashboardOverviewKpiVO totalMessages;

    private DashboardOverviewKpiVO messages24h;
}
