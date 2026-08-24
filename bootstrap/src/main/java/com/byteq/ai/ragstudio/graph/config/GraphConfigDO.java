package com.byteq.ai.ragstudio.graph.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Graph RAG 运行期配置实体
 * <p>映射数据库表 t_graph_config，保存图谱总开关与检索通道开关，
 * 由后管页面动态控制（DB 配置优先，yaml 静态值兜底）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_graph_config")
public class GraphConfigDO {

    /**
     * 主键（固定单行，如 single）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 图谱总开关：false 时构建与检索全部停用
     */
    private Boolean enabled;

    /**
     * 图谱检索通道开关（依赖图谱已构建）
     */
    private Boolean retrievalEnabled;

    /**
     * 修改人
     */
    private String updatedBy;

    /**
     * 更新时间
     */
    private Date updateTime;
}