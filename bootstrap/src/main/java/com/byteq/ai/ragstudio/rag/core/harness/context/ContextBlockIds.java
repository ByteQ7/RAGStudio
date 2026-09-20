package com.byteq.ai.ragstudio.rag.core.harness.context;

/**
 * 动态上下文块 ID 常量（Harness 与业务模块共享）
 * <p>集中管理块标识，避免字符串散落各处导致失效/激活不匹配。</p>
 */
public final class ContextBlockIds {

    /** 工作流召回候选清单（名称+描述）；选中某个工作流后失效 */
    public static final String WORKFLOW_CANDIDATES = "workflow:candidates";

    /** 待确认工作流草稿状态；保存/取消后失效 */
    public static final String WORKFLOW_DRAFT = "workflow:draft";

    /** 当前已选用工作流（简短提示，步骤正文在工具 Observation 中） */
    public static final String WORKFLOW_ACTIVE = "workflow:active";

    private ContextBlockIds() {
    }
}
