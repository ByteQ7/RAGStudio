package com.byteq.ai.ragstudio.rag.skillstore;

import java.util.List;
import java.util.Map;

/**
 * SKILL 管理端详情（当前版本 manifest + 文件树 + 运行时状态）
 */
public record SkillDetail(
        String name,
        String description,
        String skillType,
        Integer currentVersion,
        String declaredVersion,
        Boolean enabled,
        String changeLog,
        String updatedBy,
        String updateTime,
        SyncState syncState,
        Map<String, Object> manifest,
        List<SkillListItem.FileEntry> files,
        Boolean loaded,
        String errors,
        String warnings) {
}
