package com.byteq.ai.ragstudio.graph.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.byteq.ai.ragstudio.knowledge.dao.handler.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识图谱关系
 * <p>映射数据库表 t_graph_relation，关系携带证据 chunk（source_chunk_id），
 * 图谱检索结果可回链到 chunk 检索体系，复用下游去重/Rerank/引用链路。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_graph_relation")
public class GraphRelationDO {

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
     * 源实体 ID
     */
    private String sourceEntityId;

    /**
     * 目标实体 ID
     */
    private String targetEntityId;

    /**
     * 关系谓词（如 汇报给/负责/属于/审批）
     */
    private String predicate;

    /**
     * 方向：1=有向 source→target，0=无向
     */
    private Integer direction;

    /**
     * 聚合权重（重复证据累加）
     */
    private Double weight;

    /**
     * 证据原文（截断 200 字符）
     */
    private String evidence;

    /**
     * 证据 chunk ID（回链 chunk 体系）
     */
    private String sourceChunkId;

    /**
     * 来源文档 ID（级联清理用）
     */
    private String docId;

    /**
     * 扩展属性 JSON
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String extra;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}