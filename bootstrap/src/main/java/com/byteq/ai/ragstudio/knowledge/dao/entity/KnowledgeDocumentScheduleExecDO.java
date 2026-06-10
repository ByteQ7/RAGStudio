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
 * 知识库文档定时调度执行记录实体
 * <p>映射数据库表 t_knowledge_document_schedule_exec，记录每次定时同步任务的执行详情，
 * 包括执行状态、开始/结束时间、下载的文件信息以及远程文件的 ETag/Last-Modified 等变更检测数据。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document_schedule_exec")
public class KnowledgeDocumentScheduleExecDO {

    /**
     * 执行记录唯一标识（主键），使用雪花算法自动生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属定时调度记录 ID
     */
    private String scheduleId;

    /**
     * 文档 ID
     */
    private String docId;

    /**
     * 知识库 ID
     */
    private String kbId;

    /**
     * 执行状态：running / success / failed / skipped
     */
    private String status;

    /**
     * 执行信息或错误消息
     */
    private String message;

    /**
     * 开始时间
     */
    private Date startTime;

    /**
     * 结束时间
     */
    private Date endTime;

    /**
     * 下载的文件名
     */
    private String fileName;

    /**
     * 下载的文件大小（字节）
     */
    private Long fileSize;

    /**
     * 下载的文件内容哈希
     */
    private String contentHash;

    /**
     * 远程文件 ETag
     */
    private String etag;

    /**
     * 远程文件 Last-Modified 时间戳
     */
    private String lastModified;

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
