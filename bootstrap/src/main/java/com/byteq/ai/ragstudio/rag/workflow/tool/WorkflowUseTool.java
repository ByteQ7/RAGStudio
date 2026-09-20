package com.byteq.ai.ragstudio.rag.workflow.tool;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.harness.context.ContextBlock;
import com.byteq.ai.ragstudio.rag.core.harness.context.ContextBlockIds;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowJson;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowRenderer;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowService;
import com.byteq.ai.ragstudio.rag.workflow.dao.entity.WorkflowDO;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流使用工具（按名称加载完整步骤并开始按流程执行）
 * <p>
 * 命中候选清单后，Agent 调用本工具获取工作流完整步骤（渐进式披露：步骤不在召回时注入）。
 * 加载成功同时：
 * <ul>
 *   <li>失效"工作流候选清单"块 → 下一轮迭代起其余候选的名称/描述不再进入 system prompt；</li>
 *   <li>注册"当前工作流"块 → 提示模型严格按流程执行。</li>
 * </ul>
 */
@Slf4j
public class WorkflowUseTool implements Tool {

    public static final String TOOL_NAME = "workflow_use";

    private static final String DESCRIPTION =
            "加载指定工作流的完整步骤并按其执行。当[可复用工作流]候选清单中的某个工作流与用户问题匹配，"
            + "或用户明确要求使用某个工作流时调用。加载后请严格按步骤推进。";

    private final WorkflowService workflowService;
    private final AgentContext ctx;

    public WorkflowUseTool(WorkflowService workflowService, AgentContext ctx) {
        this.workflowService = workflowService;
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "工作流标识或名称（可复用工作流清单中的反引号内标识）"));
        return new JsonSchema("object", properties, List.of("name"), null, null, null);
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String name = params != null ? str(params.get("name")) : null;
        if (StrUtil.isBlank(name)) {
            return ToolResult.failure(TOOL_NAME, "缺少必填参数: name（工作流标识）");
        }
        WorkflowDO workflow = resolve(name);
        if (workflow == null) {
            return ToolResult.failure(TOOL_NAME, "未找到工作流 `" + name + "`。"
                    + availableHint() + "。若用户明确要求使用工作流，请告知其不存在；否则忽略本次调用。");
        }
        if (Boolean.FALSE.equals(workflow.getEnabled())) {
            return ToolResult.failure(TOOL_NAME, "工作流 `" + workflow.getName() + "` 已停用，无法使用。");
        }

        WorkflowDefinition definition = WorkflowJson.parse(workflow.getName(), workflow.getTitle(),
                workflow.getDescription(), workflow.getSteps());

        // 选中后清除其余候选，并注册当前工作流提示（下一轮模型调用生效）
        ctx.getDynamicContexts().deactivate(ContextBlockIds.WORKFLOW_CANDIDATES);
        ctx.getDynamicContexts().register(new ContextBlock(ContextBlockIds.WORKFLOW_ACTIVE,
                "【当前工作流】已选用工作流 `" + workflow.getName() + "`（" + workflow.getTitle()
                        + "），请严格按工具返回的步骤执行，分支条件不满足的步骤跳过。", 5));

        return ToolResult.success(TOOL_NAME, WorkflowRenderer.renderUse(workflow.getName(),
                workflow.getTitle(), workflow.getDescription(),
                definition.steps(), definition.notes()));
    }

    /** 按 name 精确匹配，回退 title 不区分大小写匹配 */
    private WorkflowDO resolve(String raw) {
        WorkflowDO workflow = workflowService.getByName(raw);
        if (workflow != null) {
            return workflow;
        }
        for (WorkflowDO item : workflowService.listEnabled()) {
            if (raw.equalsIgnoreCase(item.getTitle())) {
                return item;
            }
        }
        return null;
    }

    private String availableHint() {
        List<WorkflowDO> enabled = workflowService.listEnabled();
        if (enabled.isEmpty()) {
            return "当前没有任何可用工作流";
        }
        return "可用工作流：" + enabled.stream().map(WorkflowDO::getName).toList();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
