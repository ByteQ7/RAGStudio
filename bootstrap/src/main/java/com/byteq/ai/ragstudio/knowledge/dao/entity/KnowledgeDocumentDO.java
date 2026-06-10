package com.byteq.ai.ragstudio.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.byteq.ai.ragstudio.knowledge.dao.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库文档实体
 * <p>映射数据库表 t_knowledge_document，表示知识库中的文档记录。
 * 文档支持本地文件上传（file）和远程 URL 获取（url）两种来源，
 * 文档上传后可通过分块处理生成多个分片（Chunk）用于向量检索。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_knowledge_document")
public class KnowledgeDocumentDO {

    /**
     * 文档唯一标识（主键），使用雪花算法自动生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属知识库 ID
     */
    private String kbId;

    /**
     * 文档名称
     */
    private String docName;

    /**
     * 来源类型：file（本地文件）/ url（远程 URL 获取）
     */
    private String sourceType;

    /**
     * 来源位置（当 sourceType=url 时记录远程 URL 地址）
     */
    private String sourceLocation;

    /**
     * 是否开启定时拉取：1-启用，0-禁用
     */
    private Integer scheduleEnabled;

    /**
     * 定时表达式（cron），用于定时从远程 URL 同步文档更新
     */
    private String scheduleCron;

    /**
     * 是否启用：1-启用，0-禁用。禁用后该文档在 RAG 检索中不参与召回
     */
    private Integer enabled;

    /**
     * 分片数（chunk 数量），记录该文档被切分后生成的分片总数
     */
    private Integer chunkCount;

    /**
     * 文件地址（存储于 OSS / NFS 等文件系统的路径）
     */
    private String fileUrl;

    /**
     * 文件类型：pdf / markdown / docx / txt 等
     */
    private String fileType;

    /**
     * 文件大小（单位字节）
     */
    private Long fileSize;

    /**
     * 处理模式：chunk / pipeline
     * - chunk：使用分块策略直接分块
     * - pipeline：使用数据通道进行清洗处理
     */
    private String processMode;

    /**
     * 分块策略（仅在 processMode=chunk 时有效）
     */
    private String chunkStrategy;

    /**
     * 分块参数配置（JSON 格式，仅在 processMode=chunk 时有效）
     * 使用自定义的 JsonbTypeHandler 处理 PostgreSQL jsonb 类型
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String chunkConfig;

    /**
     * 数据通道（Pipeline）ID（仅在 processMode=pipeline 时有效）
     */
    private String pipelineId;

    /**
     * 文档处理状态：
     * - pending：待向量化
     * - running：向量化中
     * - failed：向量化失败
     * - success：向量化完成
     */
    private String status;

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

    /**
     * 是否删除：0-正常，1-删除（逻辑删除标识）
     */
    @TableLogic
    private Integer deleted;
}
