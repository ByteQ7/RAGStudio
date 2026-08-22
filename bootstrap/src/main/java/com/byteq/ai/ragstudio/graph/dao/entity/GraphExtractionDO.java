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
 * 图谱抽取结果缓存
 * <p>映射数据库表 t_graph_extraction，chunk 级缓存 LLM 抽取结果。
 * chunk_content_hash 与 t_knowledge_chunk.content_hash 同源，内容未变直接复用缓存，
 * 文档重导/重跑构建零 LLM 成本（幂等）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_graph_extraction")
public class GraphExtractionDO {

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
     * 文档 ID
     */
    private String docId;

    /**
     * chunk ID（全局唯一，作为幂等键）
     */
    private String chunkId;

    /**
     * chunk 内容哈希（幂等复用键）
     */
    private String chunkContentHash;

    /**
     * 抽取实体 JSON [{name,type,description}]
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String entityJson;

    /**
     * 抽取关系 JSON [{source,target,predicate,evidence}]
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String relationJson;

    /**
     * DONE/FAILED/SKIPPED
     */
    private String status;

    /**
     * 生成所用模型（换模型需失效重抽）
     */
    private String modelId;

    /**
     * 抽取耗时（毫秒）
     */
    private Integer durationMs;

    /**
     * 错误信息
     */
    private String errorMessage;

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