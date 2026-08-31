package com.byteq.ai.ragstudio.rag.skillstore;

/**
 * SKILL 版本信息（版本历史列表项）
 */
public record SkillVersionInfo(
        Integer version,
        String changeLog,
        Integer fileCount,
        Long totalSize,
        String createdBy,
        String createTime,
        Boolean current) {
}
