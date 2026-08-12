package com.byteq.ai.ragstudio.rag.core.skill;

/**
 * SKILL 诊断信息
 * <p>
 * ERROR — 技能无法加载（如缺 name/description、frontmatter 不可解析）
 * WARN — 技能可加载但存在问题（如命名不规范、旧格式待迁移）
 * </p>
 */
public record SkillIssue(Severity severity, String code, String message) {

    public enum Severity { ERROR, WARN }

    public static SkillIssue error(String code, String message) {
        return new SkillIssue(Severity.ERROR, code, message);
    }

    public static SkillIssue warn(String code, String message) {
        return new SkillIssue(Severity.WARN, code, message);
    }
}
