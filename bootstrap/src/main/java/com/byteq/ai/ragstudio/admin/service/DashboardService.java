package com.byteq.ai.ragstudio.admin.service;

import com.byteq.ai.ragstudio.admin.controller.vo.DashboardOverviewVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardPerformanceVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardTrendsVO;

/**
 * 仪表盘服务接口
 * <p>
 * 定义管理后台仪表盘的数据加载逻辑，提供系统运行状况的统计数据。
 * 包括核心 KPI 总览、性能指标分析和多维度趋势数据查询。
 * </p>
 *
 * @see DashboardController
 * @see DashboardOverviewVO
 * @see DashboardPerformanceVO
 * @see DashboardTrendsVO
 */
public interface DashboardService {

    /**
     * 加载仪表盘概览数据
     * <p>
     * 返回指定时间窗口内的系统核心指标，包括用户总数、活跃用户数、
     * 会话总数、消息总数及其变化趋势。支持与上一时间窗口的对比。
     * </p>
     *
     * @param window 时间窗口，如 "24h"、"7d" 等，为空时使用默认值
     * @return 仪表盘概览数据，包含各项 KPI 指标
     */
    DashboardOverviewVO loadOverview(String window);

    /**
     * 加载系统性能数据
     * <p>
     * 返回指定时间窗口内的系统性能指标，包括平均延迟、P95 延迟、
     * 成功率、错误率、无知识回答率和慢查询率。
     * </p>
     *
     * @param window 时间窗口，如 "24h"、"7d" 等，为空时使用默认值
     * @return 系统性能指标数据
     */
    DashboardPerformanceVO loadPerformance(String window);

    /**
     * 加载趋势数据
     * <p>
     * 根据指定的指标名称、时间窗口和粒度，返回对应指标的时间序列趋势数据。
     * 支持的指标包括：sessions（会话数）、messages（消息数）、
     * activeusers（活跃用户数）、avglatency（平均延迟）、quality（质量指标）。
     * 根据时间窗口大小自动选择按小时或按天聚合。
     * </p>
     *
     * @param metric      指标名称，如 sessions、messages、activeusers、avglatency、quality
     * @param window      时间窗口，如 "24h"、"7d" 等
     * @param granularity 数据粒度，可选 "hour" 或 "day"，为空时自动判断
     * @return 趋势数据，包含按时间排序的数据点序列
     */
    DashboardTrendsVO loadTrends(String metric, String window, String granularity);
}
