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
 * SKILL 主表实体
 * <p>映射 t_skill。DB 是 SKILL 的唯一事实源，当前行即当前生效版本：
 * {@code currentVersion} 指向 t_skill_version 中的版本号；
 * {@code syncedVersion} 记录最近一次成功物化到工作区磁盘的版本号，二者不等表示待同步/漂移。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_skill")
public class SkillDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 技能唯一标识 = SKILL.md frontmatter name = 工作区目录名 */
    private String name;

    /** 描述（冗余自 frontmatter，列表展示用） */
    private String description;

    /** 执行类型：http / script / command；null 表示纯知识型 */
    private String skillType;

    /** 当前生效版本号 */
    private Integer currentVersion;

    /** 停用 = 从工作区移除，Agent 不可见 */
    private Boolean enabled;

    /** 当前版本的变更说明 */
    private String changeLog;

    /** 最近一次成功物化的版本号（漂移检测水位） */
    private Integer syncedVersion;

    private String updatedBy;

    private Date updateTime;
}
