package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 趋势数据点 VO
 * <p>
 * 表示时间序列中的一个数据点，包含时间戳和对应的数值。
 * 用于图表渲染时的坐标点数据。
 * </p>
 */
@Data
@Builder
public class DashboardTrendPointVO {

    private Long ts;

    private Double value;
}
