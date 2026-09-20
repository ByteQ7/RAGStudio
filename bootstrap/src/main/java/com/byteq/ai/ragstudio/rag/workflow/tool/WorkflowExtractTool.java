package com.byteq.ai.ragstudio.rag.workflow.tool;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowDraftStore;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowJson;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowRenderer;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowService;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流提取工具（Agent 从当前对话上下文提取可复用流程，生成待确认草稿）
 * <p>
 * 只生成草稿并展示确认卡片，<b>不落库</b>；用户确认后由 {@link WorkflowSaveTool} 落库。
 * 草稿按 conversationId 存 Redis（同会话覆盖旧草稿），支持跨轮确认。
 */
@Slf4j
public class WorkflowExtractTool implements Tool {

    public static final String TOOL_NAME = "workflow_extract";

    private static final String DESCRIPTION =
            "从当前对话中提取可复用的工作流（步骤/分支），生成待确认草稿并展示给用户。"
            + "当用户明确要求把刚才的解决流程、操作步骤固定/保存/沉淀为工作流时调用。"
            + "调用后必须把返回内容中的 [WORKFLOW_CONFIRM]...[/WORKFLOW_CONFIRM] 标记原样包含在最终回答里"
            + "（它会渲染成确认卡片），并询问用户是否确认；"
            + "在用户明确确认前，禁止调用 workflow_save。";

    private final WorkflowDraftStore draftStore;
    private final WorkflowService workflowService;
    private final AgentContext ctx;

    public WorkflowExtractTool(WorkflowDraftStore draftStore, WorkflowService workflowService,
                               AgentContext ctx) {
        this.draftStore = draftStore;
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
        Map<String, Object> stepProperties = new LinkedHashMap<>();
        stepProperties.put("action", Map.of(
                "type", "string",
                "description", "本步骤要做什么（自然语言指令，尽量具体、可执行）"));
        stepProperties.put("tool", Map.of(
                "type", "string",
                "description", "建议调用的工具名（如 rag_search、web-search、MCP 工具名；不确定可不填）"));
        stepProperties.put("when", Map.of(
                "type", "string",
                "description", "分支条件：仅当该条件满足时执行本步骤（无分支则不填）"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "唯一标识，kebab-case（小写字母/数字/连字符），如 refund-dispute"));
        properties.put("title", Map.of(
                "type", "string",
                "description", "展示名（中文，如「退款争议处理」）"));
        properties.put("description", Map.of(
                "type", "string",
                "description", "这个工作流解决什么问题、什么时候使用（用于后续相似问题召回）"));
        properties.put("steps", Map.of(
                "type", "array",
                "description", "按执行顺序排列的步骤；有分支时用 when 描述条件",
                "items", Map.of(
                        "type", "object",
                        "properties", stepProperties,
                        "required", List.of("action"))));
        properties.put("notes", Map.of(
                "type", "string",
                "description", "前置条件 / 边界说明（可空）"));

        return new JsonSchema("object", properties,
                List.of("name", "title", "description", "steps"), null, null, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> params) {
        try {
            String rawName = str(params.get("name"));
            String title = str(params.get("title"));
            String description = str(params.get("description"));
            String notes = str(params.get("notes"));
            List<WorkflowDefinition.WorkflowStep> steps = parseSteps(params.get("steps"));

            String name = WorkflowJson.normalizeName(rawName);
            List<String> errors = WorkflowJson.validate(name, title, description, steps, notes);
            if (!errors.isEmpty()) {
                return ToolResult.failure(TOOL_NAME, "工作流提取失败：" + String.join("；", errors)
                        + "。请修正参数后重新调用 " + TOOL_NAME + "。");
            }

            // 同名检测：保存将覆盖已有工作流，必须让用户知情（卡片上显式提示）
            boolean overwrite = false;
            try {
                overwrite = workflowService.getByName(name) != null;
            } catch (Exception e) {
                log.warn("同名工作流检测失败（按新建处理）: {}", e.getMessage());
            }

            String stepsJson = WorkflowJson.toJson(steps, notes);
            WorkflowDraftStore.Draft draft = draftStore.save(ctx.getConversationId(), ctx.getUserId(),
                    name, title, description, stepsJson, overwrite);
            WorkflowDefinition definition = new WorkflowDefinition(name, title, description, steps, notes);

            StringBuilder sb = new StringBuilder();
            sb.append("工作流草稿已生成（draftId=").append(draft.draftId()).append("），已向用户展示确认卡片。\n");
            sb.append("请勿调用 workflow_save，等待用户明确回复确认或补充。\n");
            if (overwrite) {
                sb.append("⚠️ 注意：已存在同名工作流 `").append(name)
                        .append("`，用户确认保存将**覆盖更新**该工作流，请在回答中明确提示用户这一点。\n");
            }
            sb.append("\n");
            sb.append("名称：").append(title).append("（`").append(name).append("`）\n");
            sb.append("描述：").append(description).append("\n");
            sb.append("流程：\n");
            for (int i = 0; i < steps.size(); i++) {
                WorkflowDefinition.WorkflowStep step = steps.get(i);
                sb.append(i + 1).append(". ");
                if (StrUtil.isNotBlank(step.when())) {
                    sb.append("【条件：").append(step.when()).append("】");
                }
                sb.append(step.action());
                if (StrUtil.isNotBlank(step.tool())) {
                    sb.append("（建议工具：").append(step.tool()).append("）");
                }
                sb.append("\n");
            }
            if (StrUtil.isNotBlank(notes)) {
                sb.append("说明：").append(notes).append("\n");
            }
            sb.append("\n请在最终回答中展示以上工作流，并原样包含下面的确认标记：\n");
            sb.append(WorkflowRenderer.renderConfirmBlock(draft.draftId(), definition, overwrite));
            sb.append("\n\n随后请用户选择：确认保存 / 取消 / 补充说明。");
            return ToolResult.success(TOOL_NAME, sb.toString());
        } catch (Exception e) {
            log.warn("工作流提取失败: {}", e.getMessage(), e);
            return ToolResult.failure(TOOL_NAME,
                    "工作流提取失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowDefinition.WorkflowStep> parseSteps(Object raw) {
        List<WorkflowDefinition.WorkflowStep> steps = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return steps;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                steps.add(new WorkflowDefinition.WorkflowStep(
                        str(m.get("action")), str(m.get("tool")), str(m.get("when"))));
            }
        }
        return steps;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
