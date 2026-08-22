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
 * 知识图谱实体
 * <p>映射数据库表 t_graph_entity，每个知识库独立维护 (kb_id, canonical_name) 唯一，
 * 同名实体自然归并为同一节点，是图谱检索的锚定对象。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_graph_entity")
public class GraphEntityDO {

    /**
     * 主键 ID（雪花算法生成）
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 知识库 ID（图谱按知识库隔离）
     */
    private String kbId;

    /**
     * 规范化名称（合并/去重键）
     */
    private String canonicalName;

    /**
     * 展示名（首次出现的原文）
     */
    private String displayName;

    /**
     * 实体类型：PERSON/ORG/DEPT/ROLE/PRODUCT/PROCESS/SYSTEM/DOC/OTHER
     */
    private String entityType;

    /**
     * 实体一句话描述
     */
    private String description;

    /**
     * 别名数组 JSON（["人力资源部","人力资源","HR"]）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String aliases;

    /**
     * 扩展属性 JSON
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String extra;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}