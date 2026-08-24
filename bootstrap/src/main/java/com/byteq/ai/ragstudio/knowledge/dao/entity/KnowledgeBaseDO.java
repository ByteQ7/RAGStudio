package com.byteq.ai.ragstudio.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库实体
 * <p>映射数据库表 t_knowledge_base，表示一个 RAG 知识库。
 * 每个知识库对应一个向量集合用于向量存储，并关联一个嵌入模型用于文本向量化。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_base")
public class KnowledgeBaseDO {

    /**
     * 知识库唯一标识（主键），使用雪花算法自动生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 嵌入模型标识，如：qwen-emb-8b
     */
    private String embeddingModel;

    /**
     * 嵌入模型供应商，如：siliconflow
     */
    private String embeddingProvider;

    /**
     * 向量维度（如 1536、4096），创建时从嵌入模型配置中自动解析
     */
    private Integer dimension;

    /**
     * 向量集合名称（创建后禁止修改）
     */
    private String collectionName;

    /**
     * 是否支持图像嵌入: 1-是，0-否
     * 由嵌入模型的多模态能力自动判断
     */
    private Integer supportsImageEmbedding;

    /**
     * 文档解析引擎：AUTO/LOCAL_MINERU/REMOTE_MINERU/MULTIMODAL_LLM
     * 默认 AUTO：优先 MinerU，失败回退多模态 LLM（仅文本型知识库生效）
     */
    private String parseEngine;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 修改人
     */
    private String updatedBy;

    /**
     * 创建时间（由 MyBatis-Plus 自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    /**
     * 更新时间（由 MyBatis-Plus 自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;
}

