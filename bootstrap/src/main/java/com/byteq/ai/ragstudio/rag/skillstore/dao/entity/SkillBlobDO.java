package com.byteq.ai.ragstudio.rag.skillstore.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * SKILL 内容寻址 blob 实体
 * <p>映射 t_skill_blob。文件内容按 SHA-256 去重存储：未变更文件跨版本零冗余
 * （如 10MB geojson 在多个版本间共享同一条记录）。文本内容即 UTF-8 字节。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_skill_blob")
public class SkillBlobDO {

    @TableId(type = IdType.INPUT)
    private String sha256;

    private Long size;

    private Boolean isBinary;

    private byte[] content;

    private Date createTime;
}
