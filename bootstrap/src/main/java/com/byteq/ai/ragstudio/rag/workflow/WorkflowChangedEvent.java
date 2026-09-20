package com.byteq.ai.ragstudio.rag.workflow;

import com.byteq.ai.ragstudio.rag.workflow.dao.entity.WorkflowDO;

/**
 * 工作流变更事件
 * <p>工作流创建/更新/删除/启停后发布，触发召回索引重建（解耦 DB 写入与向量索引）。</p>
 */
public record WorkflowChangedEvent(String name, Action action) {

    public enum Action {
        CREATED, UPDATED, DELETED, TOGGLED
    }

    public static WorkflowChangedEvent of(WorkflowDO workflow, Action action) {
        return new WorkflowChangedEvent(workflow != null ? workflow.getName() : null, action);
    }
}
