package com.byteq.ai.ragstudio.rag.skillstore;

import java.util.List;

/**
 * SKILL 管理端列表项（DB 信息与运行时加载状态合并）
 */
public record SkillListItem(
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
        Boolean loaded,
        String errors,
        String warnings) {

    /** 文件清单条目（详情用） */
    public record FileEntry(String path, Boolean isBinary, Long size) {}
}
