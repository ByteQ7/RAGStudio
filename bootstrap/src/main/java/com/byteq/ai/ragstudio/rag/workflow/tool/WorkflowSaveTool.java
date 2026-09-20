package com.byteq.ai.ragstudio.rag.workflow.tool;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.harness.context.ContextBlockIds;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowDraftStore;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowJson;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowService;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工作流保存工具（用户确认后把草稿固定为正式工作流）
 * <p>
 * <b>服务端防呆</b>：不信任模型自律，确认保存时校验两点——
 * ① 会话存在待确认草稿；② 当前用户消息是肯定语义（防模型在用户未确认时擅自保存）。
 * 取消时删除草稿。两种操作都会失效上下文中的"待确认草稿"块，避免后续轮次继续注入。
 */
@Slf4j
public class WorkflowSaveTool implements Tool {

    public static final String TOOL_NAME = "workflow_save";

    /** 肯定语义（短消息内的正向确认词） */
    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "(?i)(确认|确定|保存|固定|可以|好的|好|同意|没问题|是的|是|yes|y|ok|okay|sure)");
    /** 否定/修改语义：出现即判定为非肯定回复 */
    private static final Pattern NEGATIVE = Pattern.compile(
            "(?i)(不|否|别|取消|修改|改一下|调整|等等|稍等|先不|不要|no|not)");
    /** 确认消息的最大长度：确认卡片发送的是短文本，过长消息视为"补充说明"而非确认，避免误判 */
    private static final int MAX_AFFIRMATIVE_LENGTH = 20;

    private final WorkflowDraftStore draftStore;
    private final WorkflowService workflowService;
    private final AgentContext ctx;

    public WorkflowSaveTool(WorkflowDraftStore draftStore, WorkflowService workflowService,
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
        return "把待确认的工作流草稿固定保存为正式工作流（action=confirm），或取消草稿（action=cancel）。"
                + "仅在用户明确回复确认/取消后调用；用户未确认前禁止调用 confirm。";
    }

    @Override
    public JsonSchema inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "description", "confirm=用户确认后保存；cancel=用户取消",
                "enum", List.of("confirm", "cancel")));
        properties.put("draftId", Map.of(
                "type", "string",
                "description", "草稿 ID（可空 = 取当前会话最新草稿）"));
        return new JsonSchema("object", properties, List.of("action"), null, null, null);
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String action = params != null ? str(params.get("action")) : null;
        String draftId = params != null ? str(params.get("draftId")) : null;
        if (!"confirm".equalsIgnoreCase(action) && !"cancel".equalsIgnoreCase(action)) {
            return ToolResult.failure(TOOL_NAME, "action 仅支持 confirm / cancel");
        }

        WorkflowDraftStore.Draft draft = draftStore.get(ctx.getConversationId(), draftId);
        if (draft == null) {
            return ToolResult.failure(TOOL_NAME, "当前会话没有待确认的工作流草稿（可能已过期），"
                    + "请先调用 " + WorkflowExtractTool.TOOL_NAME + " 重新提取。");
        }

        if ("cancel".equalsIgnoreCase(action)) {
            draftStore.remove(ctx.getConversationId());
            ctx.getDynamicContexts().deactivate(ContextBlockIds.WORKFLOW_DRAFT);
            return ToolResult.success(TOOL_NAME, "已取消工作流「" + draft.title() + "」，草稿已删除，未做任何保存。");
        }

        // confirm 防呆：必须是用户肯定回复
        if (!isAffirmative(ctx.getQuestion())) {
            return ToolResult.failure(TOOL_NAME, "用户尚未明确确认，禁止保存。"
                    + "请先向用户展示工作流并询问是否确认固定（用户回复确认后再调用本工具 confirm）。");
        }

        try {
            WorkflowDefinition definition = WorkflowJson.parse(draft.name(), draft.title(),
                    draft.description(), draft.stepsJson());
            var saved = workflowService.saveOrUpdate(draft.name(), draft.title(), draft.description(),
                    definition.steps(), definition.notes(), "EXTRACTED", "从对话提取并确认", ctx.getUserId());
            draftStore.remove(ctx.getConversationId());
            ctx.getDynamicContexts().deactivate(ContextBlockIds.WORKFLOW_DRAFT);

            return ToolResult.success(TOOL_NAME, "工作流已固定保存：`" + saved.getName() + "`（" + saved.getTitle() + "），"
                    + "共 " + definition.stepCount() + " 个步骤。之后遇到相似问题时我会优先参考该工作流执行。");
        } catch (Exception e) {
            log.warn("工作流保存失败: {}", e.getMessage(), e);
            return ToolResult.failure(TOOL_NAME,
                    "工作流保存失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * 当前用户消息是否为肯定语义（短消息 + 含正向确认词 + 无否定/修改词）。
     * 前端确认卡片发送的是固定文本「确认保存」，天然命中。
     */
    public static boolean isAffirmative(String question) {
        if (StrUtil.isBlank(question)) {
            return false;
        }
        String text = question.trim();
        if (text.length() > MAX_AFFIRMATIVE_LENGTH) {
            return false;
        }
        if (NEGATIVE.matcher(text).find()) {
            return false;
        }
        return AFFIRMATIVE.matcher(text).find();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
