package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 趋势数据系列 VO
 * <p>
 * 表示一个完整的数据系列，包含系列名称和一组按时间排序的数据点。
 * 例如"会话数"系列包含多天的会话数量数据点。
 * </p>
 */
@Data
@Builder
public class DashboardTrendSeriesVO {

    private String name;

    private List<DashboardTrendPointVO> data;
}
