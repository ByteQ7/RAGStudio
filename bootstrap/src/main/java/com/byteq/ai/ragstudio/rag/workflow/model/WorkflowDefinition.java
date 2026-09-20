package com.byteq.ai.ragstudio.rag.workflow.model;

import java.util.List;

/**
 * 工作流定义（结构化）
 *
 * @param name        唯一标识（kebab-case）
 * @param title       展示名
 * @param description 何时使用
 * @param steps       步骤列表（可含分支条件）
 * @param notes       前置条件 / 边界说明（可空）
 */
public record WorkflowDefinition(String name, String title, String description,
                                 List<WorkflowStep> steps, String notes) {

    /**
     * 单个步骤
     *
     * @param action 本步骤要做什么（自然语言指令）
     * @param tool   建议调用的工具名（可空 = 由 Agent 自行判断）
     * @param when   分支条件：仅当条件满足时执行本步骤（可空 = 顺序执行）
     */
    public record WorkflowStep(String action, String tool, String when) {
    }

    /** 步骤数量（防御 null） */
    public int stepCount() {
        return steps != null ? steps.size() : 0;
    }
}
