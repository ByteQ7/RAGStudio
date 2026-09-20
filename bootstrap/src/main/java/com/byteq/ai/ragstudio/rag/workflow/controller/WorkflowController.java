package com.byteq.ai.ragstudio.rag.workflow.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowAdminService;
import com.byteq.ai.ragstudio.rag.workflow.dao.entity.WorkflowDO;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraph;
import com.byteq.ai.ragstudio.rag.workflow.graph.WorkflowGraphValidator;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import com.byteq.ai.ragstudio.user.constant.RoleConstant;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作流管理控制器
 * <p>工作流是 Agent 从对话中沉淀的可复用流程（见 docs/workflow-harness-design.md）；
 * 运行链路（召回注入、提取/保存/使用工具）不经过本控制器。</p>
 */
@Slf4j
@RestController
@SaCheckRole(RoleConstant.ADMIN)
@RequiredArgsConstructor
@RequestMapping("/admin/workflows")
public class WorkflowController {

    private final WorkflowAdminService workflowAdminService;

    /** 工作流列表 */
    @GetMapping
    public Result<List<WorkflowAdminService.WorkflowListItem>> list() {
        return Results.success(workflowAdminService.list());
    }

    /** 工作流详情（含步骤） */
    @GetMapping("/{name}")
    public Result<WorkflowAdminService.WorkflowDetail> detail(@PathVariable String name) {
        return Results.success(workflowAdminService.detail(name));
    }

    /** 手工新建（name 由服务端规范化为 kebab-case，返回规范化后的实体） */
    @PostMapping
    public Result<WorkflowAdminService.WorkflowDetail> create(@RequestBody WorkflowRequest request) {
        WorkflowDO created = workflowAdminService.create(request.getName(), request.getTitle(),
                request.getDescription(), request.getSteps(), request.getNotes(),
                request.getGraph(), UserContext.getUsername());
        return Results.success(workflowAdminService.detail(created.getName()));
    }

    /** 编辑（覆盖步骤并重建向量） */
    @PutMapping("/{name}")
    public Result<WorkflowAdminService.WorkflowDetail> update(@PathVariable String name,
                                                              @RequestBody WorkflowRequest request) {
        workflowAdminService.update(name, request.getTitle(), request.getDescription(),
                request.getSteps(), request.getNotes(), request.getGraph(),
                request.getChangeLog(), UserContext.getUsername());
        return Results.success(workflowAdminService.detail(name));
    }

    /** 删除 */
    @DeleteMapping("/{name}")
    public Result<Void> delete(@PathVariable String name) {
        workflowAdminService.delete(name);
        return Results.success();
    }

    /** 启停（停用后不参与召回） */
    @PostMapping("/{name}/toggle")
    public Result<WorkflowAdminService.WorkflowDetail> toggle(@PathVariable String name,
                                                              @RequestBody ToggleRequest request) {
        workflowAdminService.toggle(name, request.isEnabled());
        return Results.success(workflowAdminService.detail(name));
    }

    /** 仅校验画布图（画布"校验"按钮），返回 ERROR/WARN 列表，不落库 */
    @PostMapping("/validate")
    public Result<WorkflowGraphValidator.ValidationResult> validateGraph(@RequestBody WorkflowRequest request) {
        return Results.success(workflowAdminService.validateGraph(request.getGraph()));
    }

    /** 重建召回索引（模型切换后手动触发） */
    @PostMapping("/rebuild-index")
    public Result<Integer> rebuildIndex() {
        int size = workflowAdminService.rebuildIndex();
        log.info("手动重建工作流召回索引, operator={}, indexed={}", UserContext.getUsername(), size);
        return Results.success(size);
    }

    /** 新建/编辑请求体 */
    @Data
    public static class WorkflowRequest {
        private String name;
        private String title;
        private String description;
        private List<WorkflowDefinition.WorkflowStep> steps;
        private String notes;
        private String changeLog;
        /** 画布图；提供时 steps 以服务端编译结果为准（见 docs/workflow-canvas-design.md） */
        private WorkflowGraph graph;
    }

    /** 启停请求体 */
    @Data
    public static class ToggleRequest {
        private boolean enabled;
    }
}
