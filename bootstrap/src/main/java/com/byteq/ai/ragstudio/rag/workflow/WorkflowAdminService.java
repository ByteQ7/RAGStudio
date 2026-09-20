package com.byteq.ai.ragstudio.rag.workflow;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.workflow.dao.entity.WorkflowDO;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraph;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraphValidator;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流管理编排（管理端 API 的用例层）
 * <p>承载列表/详情/新建/编辑/删除/启停/索引重建的编排逻辑，Controller 只做参数与响应组装。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAdminService {

    private final WorkflowService workflowService;
    private final WorkflowRecallService recallService;

    /** 列表项（不含 steps/embedding 大字段） */
    public record WorkflowListItem(Long id, String name, String title, String description,
                                   String source, Boolean enabled, Integer stepCount,
                                   String changeLog, String updatedBy, String updateTime,
                                   boolean indexed) {
    }

    /** 详情（含 steps、解析后的定义与画布图） */
    public record WorkflowDetail(Long id, String name, String title, String description,
                                 List<WorkflowDefinition.WorkflowStep> steps, String notes,
                                 WorkflowGraph graph,
                                 String source, Boolean enabled, String changeLog,
                                 String updatedBy, String createTime, String updateTime) {
    }

    public List<WorkflowListItem> list() {
        List<WorkflowListItem> result = new ArrayList<>();
        for (WorkflowDO workflow : workflowService.listAll()) {
            WorkflowDefinition definition = WorkflowJson.parse(workflow.getName(), workflow.getTitle(),
                    workflow.getDescription(), workflow.getSteps());
            result.add(new WorkflowListItem(workflow.getId(), workflow.getName(), workflow.getTitle(),
                    workflow.getDescription(), workflow.getSource(), workflow.getEnabled(),
                    definition.stepCount(), workflow.getChangeLog(), workflow.getUpdatedBy(),
                    formatDate(workflow.getUpdateTime()),
                    StrUtil.isNotBlank(workflow.getEmbedding())));
        }
        return result;
    }

    public WorkflowDetail detail(String name) {
        WorkflowDO workflow = require(name);
        WorkflowDefinition definition = WorkflowJson.parse(workflow.getName(), workflow.getTitle(),
                workflow.getDescription(), workflow.getSteps());
        return new WorkflowDetail(workflow.getId(), workflow.getName(), workflow.getTitle(),
                workflow.getDescription(), definition.steps(), definition.notes(),
                workflowService.resolveGraph(workflow),
                workflow.getSource(), workflow.getEnabled(), workflow.getChangeLog(),
                workflow.getUpdatedBy(), formatDate(workflow.getCreateTime()),
                formatDate(workflow.getUpdateTime()));
    }

    public WorkflowDO create(String name, String title, String description,
                             List<WorkflowDefinition.WorkflowStep> steps, String notes,
                             WorkflowGraph graph, String operator) {
        String normalized = WorkflowJson.normalizeName(name);
        if (workflowService.getByName(normalized) != null) {
            throw new ClientException("工作流已存在：" + normalized);
        }
        return workflowService.saveOrUpdate(normalized, title, description, steps, notes, graph,
                "MANUAL", "后管新建", operator);
    }

    public WorkflowDO create(String name, String title, String description,
                             List<WorkflowDefinition.WorkflowStep> steps, String notes,
                             String operator) {
        return create(name, title, description, steps, notes, null, operator);
    }

    public WorkflowDO update(String name, String title, String description,
                             List<WorkflowDefinition.WorkflowStep> steps, String notes,
                             WorkflowGraph graph, String changeLog, String operator) {
        return workflowService.saveOrUpdate(name, title, description, steps, notes, graph,
                null, StrUtil.blankToDefault(changeLog, "后管编辑"), operator);
    }

    public WorkflowDO update(String name, String title, String description,
                             List<WorkflowDefinition.WorkflowStep> steps, String notes,
                             String changeLog, String operator) {
        return update(name, title, description, steps, notes, null, changeLog, operator);
    }

    /** 仅校验画布图（画布"校验"按钮），不落库 */
    public WorkflowGraphValidator.ValidationResult validateGraph(WorkflowGraph graph) {
        return WorkflowGraphValidator.validate(graph);
    }

    public void delete(String name) {
        workflowService.delete(name);
    }

    public WorkflowDO toggle(String name, boolean enabled) {
        return workflowService.toggle(name, enabled);
    }

    /** 重建召回索引（模型切换 / 手工触发） */
    public int rebuildIndex() {
        recallService.rebuildIndex();
        return recallService.size();
    }

    private WorkflowDO require(String name) {
        WorkflowDO workflow = workflowService.getByName(name);
        if (workflow == null) {
            throw new ClientException("工作流不存在：" + name);
        }
        return workflow;
    }

    private static String formatDate(java.util.Date date) {
        if (date == null) {
            return null;
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }
}
