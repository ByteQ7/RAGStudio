package com.byteq.ai.ragstudio.framework.trace;

/**
 * 链路追踪状态枚举
 * <p>
 * 统一管理 Run 和 Node 的所有生命周期状态。
 * 终端状态（SUCCESS/ERROR/CANCELLED/TIMEOUT）不可再转换回 RUNNING。
 * </p>
 */
public enum TraceStatus {

    RUNNING,
    SUCCESS,
    ERROR,
    CANCELLED,
    TIMEOUT;

    /**
     * 判断当前状态是否为终端状态（不可再转换）
     */
    public boolean isTerminal() {
        return this != RUNNING;
    }

    /**
     * 判断当前状态是否表示失败（ERROR / TIMEOUT / CANCELLED）
     */
    public boolean isFailed() {
        return this == ERROR || this == TIMEOUT || this == CANCELLED;
    }
}
