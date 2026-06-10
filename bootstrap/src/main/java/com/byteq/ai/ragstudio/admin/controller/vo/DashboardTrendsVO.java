package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 趋势数据 VO
 * <p>
 * 封装指定指标的时间序列趋势数据，包含多个数据系列，
 * 每个系列由按时间排序的数据点组成。
 * </p>
 */
@Data
@Builder
public class DashboardTrendsVO {

    private String metric;

    private String window;

    private String granularity;

    private List<DashboardTrendSeriesVO> series;
}
