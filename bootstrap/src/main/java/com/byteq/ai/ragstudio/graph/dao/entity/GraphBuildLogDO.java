package com.byteq.ai.ragstudio.graph.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 图谱构建任务日志
 * <p>映射数据库表 t_graph_build_log，记录每次图谱构建/增量更新的统计信息与失败原因，
 * 用于管理端展示与成本统计。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_graph_build_log")
public class GraphBuildLogDO {

    /**
     * 主键 ID（雪花算法生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 知识库 ID
     */
    private String kbId;

    /**
     * 触发类型：DOC(单文档)/KB(全库重建)/CHUNK(单块)
     */
    private String triggerType;

    /**
     * 文档 ID（可选）
     */
    private String docId;

    /**
     * RUNNING/SUCCESS/FAILED
     */
    private String status;

    /**
     * 新增实体数
     */
    private Integer entityAdded;

    /**
     * 合并（复用）实体数
     */
    private Integer entityMerged;

    /**
     * 新增关系数
     */
    private Integer relationAdded;

    /**
     * 删除关系数
     */
    private Integer relationRemoved;

    /**
     * LLM 调用次数（成本统计）
     */
    private Integer llmCalls;

    /**
     * 总耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private Date createTime;
}