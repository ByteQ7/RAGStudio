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
 * 知识库文档定时调度实体
 * <p>映射数据库表 t_knowledge_document_schedule，管理文档定时同步任务的调度配置和执行状态。
 * 支持基于 Cron 表达式的定时调度，包含分布式锁机制防止多实例重复执行，
 * 并通过 ETag/Last-Modified/内容哈希实现增量同步。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document_schedule")
public class KnowledgeDocumentScheduleDO {

    /**
     * 调度记录唯一标识（主键），使用雪花算法自动生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 文档 ID
     */
    private String docId;

    /**
     * 知识库 ID
     */
    private String kbId;

    /**
     * 定时 Cron 表达式，定义文档刷新频率
     */
    private String cronExpr;

    /**
     * 是否启用定时调度：1-启用，0-禁用
     */
    private Integer enabled;

    /**
     * 下次执行时间
     */
    private Date nextRunTime;

    /**
     * 上次执行时间
     */
    private Date lastRunTime;

    /**
     * 上次成功执行时间
     */
    private Date lastSuccessTime;

    /**
     * 上次执行状态（success / failed / skipped）
     */
    private String lastStatus;

    /**
     * 上次错误信息
     */
    private String lastError;

    /**
     * 上次远程文件 ETag（用于检测远程文件是否变更）
     */
    private String lastEtag;

    /**
     * 上次远程文件 Last-Modified 时间戳（用于检测远程文件是否变更）
     */
    private String lastModified;

    /**
     * 上次内容哈希值（用于内容级别去重）
     */
    private String lastContentHash;

    /**
     * 分布式锁持有者标识（用于多实例环境下的任务互斥）
     */
    private String lockOwner;

    /**
     * 分布式锁到期时间
     */
    private Date lockUntil;

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
