package com.byteq.ai.ragstudio.knowledge.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 分块策略视图对象
 * <p>用于向前端返回系统支持的分块策略信息，包含策略值、显示名称和默认配置参数。</p>
 */
@Data
@AllArgsConstructor
public class ChunkStrategyVO {

    /**
     * 分块策略值，如：fixed_size、structure_aware
     */
    private String value;

    /**
     * 分块策略显示名称，如：固定大小分块、结构感知分块
     */
    private String label;

    /**
     * 分块策略的默认配置参数（键值对形式）
     */
    private Map<String, Integer> defaultConfig;
}
