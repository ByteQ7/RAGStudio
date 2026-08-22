package com.byteq.ai.ragstudio.graph.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.byteq.ai.ragstudio.knowledge.dao.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识图谱社区（全局检索预留）
 * <p>映射数据库表 t_graph_community，缓存社区发现结果与 LLM 社区摘要，
 * 供全局检索（聚合性问题）使用，默认不启用。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_graph_community")
public class GraphCommunityDO {

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
     * 社区 ID
     */
    private String communityId;

    /**
     * 社区层级（LCC 分层）
     */
    private Integer level;

    /**
     * LLM 生成的社区摘要
     */
    private String summary;

    /**
     * 成员实体数
     */
    private Integer entityCount;

    /**
     * 成员实体 ID 列表 JSON
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String entityIds;

    /**
     * 所属构建版本
     */
    private String buildId;

    /**
     * 创建时间
     */
    private Date createTime;
}