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
 * 知识库文档分块日志实体
 * <p>映射数据库表 t_knowledge_document_chunk_log，记录文档每次分块处理的详细执行日志，
 * 包含各处理阶段（文本提取、分块、向量嵌入、DB 持久化）的耗时统计和结果信息，
 * 用于监控和排查文档分块处理性能问题。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document_chunk_log")
public class KnowledgeDocumentChunkLogDO {

    /**
     * 日志记录唯一标识（主键），使用雪花算法自动生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 文档 ID
     */
    private String docId;

    /**
     * 执行状态：running / success / failed
     */
    private String status;

    /**
     * 处理模式：chunk（分块策略）/ pipeline（数据通道）
     */
    private String processMode;

    /**
     * 分块策略（仅在 processMode=chunk 时有效）
     */
    private String chunkStrategy;

    /**
     * 数据通道 Pipeline ID（仅在 processMode=pipeline 时有效）
     */
    private String pipelineId;

    /**
     * 文本提取阶段耗时（毫秒）
     */
    private Long extractDuration;

    /**
     * 文本分块阶段耗时（毫秒）
     */
    private Long chunkDuration;

    /**
     * 向量嵌入 API 调用耗时（毫秒）
     */
    private Long embedDuration;

    /**
     * 数据库持久化耗时（毫秒）
     */
    private Long persistDuration;

    /**
     * 分块处理总耗时（毫秒）
     */
    private Long totalDuration;

    /**
     * 生成的分片数量
     */
    private Integer chunkCount;

    /**
     * 错误信息（处理失败时记录详细原因）
     */
    private String errorMessage;

    /**
     * 分块处理开始时间
     */
    private Date startTime;

    /**
     * 分块处理结束时间
     */
    private Date endTime;

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
