package com.byteq.ai.ragstudio.rag.workflow;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流文本渲染
 * <p>
 * 负责三类输出：
 * <ul>
 *   <li>{@link #renderCandidateList} 召回候选清单（只含名称+描述，注入 system prompt）</li>
 *   <li>{@link #renderUse} 选中后加载的完整步骤（工具 Observation）</li>
 *   <li>{@link #renderConfirmBlock} 确认卡片标记（前端解析渲染，JSON 载荷）</li>
 * </ul>
 */
public final class WorkflowRenderer {

    public static final String CONFIRM_START = "[WORKFLOW_CONFIRM]";
    public static final String CONFIRM_END = "[/WORKFLOW_CONFIRM]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowRenderer() {
    }

    /** 候选清单（名称 + 描述 + 使用指引）；candidates 为空返回空串 */
    public static String renderCandidateList(List<Card> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【可复用工作流】以下工作流与当前问题可能相关：\n");
        for (int i = 0; i < candidates.size(); i++) {
            Card card = candidates.get(i);
            sb.append(i + 1).append(". `").append(card.name()).append("`（").append(card.title()).append("）")
                    .append("：").append(card.description()).append("\n");
        }
        sb.append("若其中某个工作流的适用场景与用户问题匹配，请先调用 workflow_use 加载其完整步骤，"
                + "并严格按步骤执行；若都不匹配，请忽略本段，按正常流程回答。");
        return sb.toString();
    }

    /** 完整步骤（workflow_use 返回）：供 Agent 按步骤执行 */
    public static String renderUse(String name, String title, String description,
                                   List<WorkflowDefinition.WorkflowStep> steps, String notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("工作流 `").append(name).append("`（").append(title).append("）已加载，请严格按以下步骤执行：\n");
        sb.append("适用场景：").append(description).append("\n");
        if (StrUtil.isNotBlank(notes)) {
            sb.append("前置说明：").append(notes).append("\n");
        }
        sb.append("步骤：\n");
        if (steps != null) {
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
        }
        sb.append("执行要求：按顺序推进，命中条件分支时只执行对应步骤；步骤中建议的工具不可用时，"
                + "自行选择等效工具完成该步骤目标，不要跳过步骤。");
        return sb.toString();
    }

    /**
     * 确认卡片标记：前端解析 JSON 载荷渲染工作流预览 + YES/NO/Other 三个操作。
     * 模型被要求将该标记原样输出到最终回答中。
     */
    public static String renderConfirmBlock(String draftId, WorkflowDefinition definition,
                                            boolean overwrite) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftId", draftId);
        payload.put("name", definition.name());
        payload.put("title", definition.title());
        payload.put("description", definition.description());
        payload.put("notes", definition.notes());
        payload.put("steps", definition.steps());
        payload.put("overwrite", overwrite);
        try {
            return CONFIRM_START + "\n" + MAPPER.writeValueAsString(payload) + "\n" + CONFIRM_END;
        } catch (Exception e) {
            throw new IllegalStateException("工作流确认标记序列化失败", e);
        }
    }

    /** 候选卡片（最小字段集） */
    public record Card(String name, String title, String description, double score) {
    }
}
