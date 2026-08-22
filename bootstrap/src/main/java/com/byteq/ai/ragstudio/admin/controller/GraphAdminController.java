package com.byteq.ai.ragstudio.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphBuildLogVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphEntityVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphOverviewVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphSubgraphVO;
import com.byteq.ai.ragstudio.admin.service.GraphAdminService;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.graph.service.GraphExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 图谱管理控制器
 * <p>提供知识库图谱的构建触发、统计概览、实体管理（合并）与可视化数据查询。
 * 仅管理员可访问。</p>
 */
@Slf4j
@RestController
@SaCheckRole("admin")
@RequiredArgsConstructor
@RequestMapping("/admin/graph")
public class GraphAdminController {

    private final GraphAdminService graphAdminService;
    private final GraphExtractionService graphExtractionService;

    /**
     * 图谱统计概览
     */
    @GetMapping("/kb/{kbId}/overview")
    public Result<GraphOverviewVO> overview(@PathVariable("kbId") String kbId) {
        return Results.success(graphAdminService.overview(kbId));
    }

    /**
     * 全量重建图谱（异步）
     */
    @PostMapping("/kb/{kbId}/rebuild")
    public Result<String> rebuild(@PathVariable("kbId") String kbId) {
        log.info("手动触发知识库图谱全量重建: kbId={}", kbId);
        return Results.success(graphExtractionService.rebuildKnowledgeBase(kbId));
    }

    /**
     * 实体分页查询
     */
    @GetMapping("/kb/{kbId}/entities")
    public Result<IPage<GraphEntityVO>> entities(@PathVariable("kbId") String kbId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String entityType,
                                                 @RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "20") long size) {
        return Results.success(graphAdminService.pageEntities(kbId, keyword, entityType, current, size));
    }

    /**
     * 实体详情
     */
    @GetMapping("/entities/{entityId}")
    public Result<GraphEntityVO> entity(@PathVariable("entityId") String entityId) {
        return Results.success(graphAdminService.getEntity(entityId));
    }

    /**
     * 合并实体
     */
    @PostMapping("/entities/merge")
    public Result<Void> merge(@RequestBody Map<String, Object> request) {
        String kbId = (String) request.get("kbId");
        String keepEntityId = (String) request.get("keepEntityId");
        @SuppressWarnings("unchecked")
        List<String> mergeEntityIds = (List<String>) request.get("mergeEntityIds");
        graphAdminService.mergeEntities(kbId, keepEntityId, mergeEntityIds);
        return Results.success();
    }

    /**
     * 子图可视化数据（mermaid 渲染）
     */
    @GetMapping("/kb/{kbId}/graph")
    public Result<GraphSubgraphVO> subgraph(@PathVariable("kbId") String kbId,
                                            @RequestParam(required = false) String focusEntityId,
                                            @RequestParam(defaultValue = "200") int maxNodes) {
        return Results.success(graphAdminService.subgraph(kbId, focusEntityId, maxNodes));
    }

    /**
     * 构建日志分页
     */
    @GetMapping("/kb/{kbId}/build-logs")
    public Result<IPage<GraphBuildLogVO>> buildLogs(@PathVariable("kbId") String kbId,
                                                    @RequestParam(defaultValue = "1") long current,
                                                    @RequestParam(defaultValue = "20") long size) {
        return Results.success(graphAdminService.pageBuildLogs(kbId, current, size));
    }
}