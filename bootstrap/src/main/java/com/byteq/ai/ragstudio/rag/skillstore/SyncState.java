package com.byteq.ai.ragstudio.rag.skillstore;

/**
 * SKILL 工作区同步状态
 * <ul>
 *   <li>SYNCED — 工作区与 DB 当前版本一致</li>
 *   <li>PENDING_SYNC — DB 已更新但物化未完成/失败，待同步</li>
 *   <li>DRIFTED — 工作区被带外修改（synced_version 相同但磁盘内容不同）</li>
 *   <li>UNMANAGED — 工作区存在但未入库（可在后管收编）</li>
 *   <li>RUNTIME_ONLY — DB 不可用，仅返回运行时数据</li>
 * </ul>
 */
public enum SyncState {
    SYNCED,
    PENDING_SYNC,
    DRIFTED,
    UNMANAGED,
    RUNTIME_ONLY
}
