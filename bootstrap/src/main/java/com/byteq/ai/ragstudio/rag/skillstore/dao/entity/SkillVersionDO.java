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
 * SKILL 版本表实体
 * <p>映射 t_skill_version。每次保存/回滚/导入产生一行，全量保留（含当前版本）。
 * {@code manifest} 为解析后的元数据快照（frontmatter + skill.yaml + 目录扫描结果，JSON 字符串），
 * 供详情页与树级 diff 免重复解析。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_skill_version")
public class SkillVersionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skillId;

    /** 版本号，从 1 开始单调递增；回滚同样产生新版本 */
    private Integer version;

    /** 版本说明（回滚自动填"回滚自 vN"） */
    private String changeLog;

    private Integer fileCount;

    private Long totalSize;

    /** 解析后的元数据快照（JSON 字符串） */
    private String manifest;

    /** 全目录树 SHA-256（物化完整性校验、漂移检测） */
    private String treeHash;

    private String createdBy;

    private Date createTime;
}
