package com.byteq.ai.ragstudio.rag.skillstore.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SKILL 版本文件表实体
 * <p>映射 t_skill_file。一个版本一份文件清单，内容按 sha256 存于 t_skill_blob。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_skill_file")
public class SkillFileDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long versionId;

    /** 冗余列，便于按 skill 级联清理与 blob GC */
    private Long skillId;

    /** 相对路径，POSIX '/' 分隔，如 scripts/geo_reverse.py */
    private String filePath;

    /** 文本/二进制分类（二进制不参与内容 diff） */
    private Boolean isBinary;

    private Long size;

    /** → t_skill_blob.sha256 */
    private String blobHash;
}
