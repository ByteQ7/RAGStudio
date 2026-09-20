package com.byteq.ai.ragstudio.rag.workflow;

import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.harness.context.ContextBlock;
import com.byteq.ai.ragstudio.rag.core.harness.context.ContextBlockIds;
import com.byteq.ai.ragstudio.rag.core.harness.tool.HarnessToolProvider;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.workflow.tool.WorkflowExtractTool;
import com.byteq.ai.ragstudio.rag.workflow.tool.WorkflowSaveTool;
import com.byteq.ai.ragstudio.rag.workflow.tool.WorkflowUseTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流工具提供者（Harness 工具扩展点实现）
 * <p>
 * 向 Agent 注册工作流三件套：提取 / 保存 / 使用；
 * 同时把"当前会话存在待确认草稿"注入动态上下文（用户确认/取消后失效），
 * 让模型在跨轮对话中始终知道草稿状态，无需依赖其记忆。
 */
@Slf4j
@Component
public class WorkflowToolProvider implements HarnessToolProvider {

    private final WorkflowService workflowService;
    private final WorkflowDraftStore draftStore;

    @Value("${rag.workflow.enabled:true}")
    private boolean enabled;

    public WorkflowToolProvider(WorkflowService workflowService, WorkflowDraftStore draftStore) {
        this.workflowService = workflowService;
        this.draftStore = draftStore;
    }

    @Override
    public List<Tool> provideTools(AgentContext ctx) {
        if (!enabled) {
            return List.of();
        }
        List<Tool> tools = new ArrayList<>(3);
        tools.add(new WorkflowExtractTool(draftStore, workflowService, ctx));
        tools.add(new WorkflowSaveTool(draftStore, workflowService, ctx));
        tools.add(new WorkflowUseTool(workflowService, ctx));

        // 待确认草稿状态注入：确认/取消后由保存工具失效
        try {
            WorkflowDraftStore.Draft draft = draftStore.getLatest(ctx.getConversationId());
            if (draft != null) {
                ctx.getDynamicContexts().register(new ContextBlock(ContextBlockIds.WORKFLOW_DRAFT,
                        "【待确认工作流】当前会话有一个待确认的工作流草稿：`" + draft.name() + "`（"
                                + draft.title() + "）。用户确认时调用 " + WorkflowSaveTool.TOOL_NAME
                                + "（action=confirm）；用户取消时调用 " + WorkflowSaveTool.TOOL_NAME
                                + "（action=cancel）；用户提出补充修改时调用 " + WorkflowExtractTool.TOOL_NAME
                                + " 重新提取。", 8));
            }
        } catch (Exception e) {
            log.warn("待确认工作流草稿状态注入失败: {}", e.getMessage());
        }
        return tools;
    }
}
