package com.byteq.ai.ragstudio.graph.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Graph RAG 运行期配置视图
 * <p>返回给前端展示/编辑的配置，enabled/retrievalEnabled 为生效值
 * （DB 配置优先，缺失回退 yaml 静态默认）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphConfigVO {

    /**
     * 图谱总开关（生效值）
     */
    private Boolean enabled;

    /**
     * 图谱检索通道开关（生效值）
     */
    private Boolean retrievalEnabled;

    /**
     * 抽取/查询实体识别使用的模型 ID（graph_extract 场景）；
     * 为 null 表示跟随对话默认模型
     */
    private String extractModelId;

    /**
     * 抽取模型名称（展示用）
     */
    private String extractModelName;

    /**
     * 是否跟随对话默认模型（未单独指定 graph_extract 场景）
     */
    private Boolean followsChatDefault;

    /**
     * 对话默认模型 ID（回退目标展示用）
     */
    private String chatModelId;

    /**
     * 对话默认模型名称（回退目标展示用）
     */
    private String chatModelName;
}