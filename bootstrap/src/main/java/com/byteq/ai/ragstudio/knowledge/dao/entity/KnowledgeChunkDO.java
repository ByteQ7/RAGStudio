package com.byteq.ai.ragstudio.knowledge.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 知识库文档分片实体
 * <p>映射数据库表 t_knowledge_chunk，表示文档经过分块策略处理后生成的文本分片。
 * 每个分片包含一段连续的文本内容及其向量表示，是 RAG 向量检索的最小数据单元。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_knowledge_chunk")
public class KnowledgeChunkDO {

    /**
     * 分片唯一标识（主键），使用雪花算法自动生成
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 所属知识库 ID
     */
    private String kbId;

    /**
     * 所属文档 ID
     */
    private String docId;

    /**
     * 分片序号（从 0 开始，表示在文档中的位置顺序）
     */
    private Integer chunkIndex;

    /**
     * 分片正文内容
     */
    private String content;

    /**
     * 内容哈希值（用于幂等判断和内容去重）
     */
    private String contentHash;

    /**
     * 字符数（可用于统计分析和分块参数调优）
     */
    private Integer charCount;

    /**
     * Token 数（可选字段，用于 LLM 上下文窗口计算参考）
     */
    private Integer tokenCount;

    /**
     * 是否启用：0-禁用，1-启用。禁用后该分片在向量检索中不再被召回
     */
    private Integer enabled;

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
