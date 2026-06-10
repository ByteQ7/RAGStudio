package com.byteq.ai.ragstudio.admin.controller;

import com.byteq.ai.ragstudio.admin.controller.vo.DashboardOverviewVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardPerformanceVO;
import com.byteq.ai.ragstudio.admin.controller.vo.DashboardTrendsVO;
import com.byteq.ai.ragstudio.admin.service.DashboardService;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台仪表盘控制器
 * <p>
 * 提供系统运行数据的可视化接口，包括数据概览、性能指标和趋势分析。
 * 所有接口均需要 admin 角色权限。
 * </p>
 *
 * <p>
 * 接口说明：
 * <ul>
 *   <li><b>/overview：</b>获取系统核心 KPI 总览，包括用户数、会话数、消息数等</li>
 *   <li><b>/performance：</b>获取系统性能指标，包括平均延迟、P95 延迟、成功率等</li>
 *   <li><b>/trends：</b>获取指定指标的时间序列趋势数据</li>
 * </ul>
 * </p>
 *
 * @see DashboardService
 * @see DashboardOverviewVO
 * @see DashboardPerformanceVO
 * @see DashboardTrendsVO
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取仪表盘概览数据
     * <p>
     * 返回指定时间窗口内的系统核心 KPI 指标，包括用户数、会话数、消息数等。
     * 支持与上一时间窗口的数据对比。
     * </p>
     *
     * @param window 时间窗口，如 "24h"、"7d" 等，为空时使用默认值
     * @return 仪表盘概览数据
     */
    @GetMapping("/overview")
    public Result<DashboardOverviewVO> overview(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadOverview(window));
    }

    /**
     * 获取系统性能指标
     * <p>
     * 返回指定时间窗口内的系统性能数据，包括平均延迟、P95延迟、
     * 成功率、错误率、无知识率和慢查询率。
     * </p>
     *
     * @param window 时间窗口，如 "24h"、"7d" 等，为空时使用默认值
     * @return 系统性能指标数据
     */
    @GetMapping("/performance")
    public Result<DashboardPerformanceVO> performance(@RequestParam(required = false) String window) {
        return Results.success(dashboardService.loadPerformance(window));
    }

    /**
     * 获取指标趋势数据
     * <p>
     * 返回指定指标在时间窗口内的趋势序列数据，支持按小时或按天粒度聚合。
     * 可用于前端渲染折线图等趋势可视化组件。
     * </p>
     *
     * @param metric      指标名称，如 sessions、messages、activeusers、avglatency、quality
     * @param window      时间窗口，如 "24h"、"7d" 等
     * @param granularity 数据粒度，可选 "hour" 或 "day"，为空时自动判断
     * @return 趋势数据，包含时间序列数据点
     */
    @GetMapping("/trends")
    public Result<DashboardTrendsVO> trends(@RequestParam String metric,
                                            @RequestParam(required = false) String window,
                                            @RequestParam(required = false) String granularity) {
        return Results.success(dashboardService.loadTrends(metric, window, granularity));
    }
}
