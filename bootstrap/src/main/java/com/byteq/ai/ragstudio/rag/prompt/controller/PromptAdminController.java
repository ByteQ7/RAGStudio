package com.byteq.ai.ragstudio.rag.prompt.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.prompt.config.PromptConfigService;
import com.byteq.ai.ragstudio.rag.prompt.controller.request.PromptConfigUpdateRequest;
import com.byteq.ai.ragstudio.rag.prompt.controller.vo.PromptConfigVO;
import com.byteq.ai.ragstudio.rag.prompt.controller.vo.PromptHistoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提示词管理接口
 * <p>提供提示词列表、编辑、重置、变更历史与回滚能力，供后管「提示词管理」页使用。
 * 编辑/重置/回滚后立即热重载，无需重启服务。仅管理员可访问。</p>
 */
@Slf4j
@RestController
@SaCheckRole("admin")
@RequiredArgsConstructor
@RequestMapping("/admin/prompts")
public class PromptAdminController {

    private final PromptConfigService promptConfigService;

    /**
     * 提示词全量列表（按分类可筛选）
     */
    @GetMapping
    public Result<List<PromptConfigVO>> list(@RequestParam(required = false) String category,
                                             @RequestParam(required = false) String keyword) {
        List<PromptConfigVO> all = promptConfigService.list();
        List<PromptConfigVO> filtered = all.stream()
                .filter(v -> category == null || category.isBlank() || category.equals(v.getCategory()))
                .filter(v -> keyword == null || keyword.isBlank()
                        || v.getKey().contains(keyword) || v.getName().contains(keyword)
                        || (v.getDescription() != null && v.getDescription().contains(keyword)))
                .toList();
        return Results.success(filtered);
    }

    /**
     * 提示词详情（含出厂默认内容，供编辑对比）
     */
    @GetMapping("/{key}")
    public Result<PromptConfigVO> detail(@PathVariable String key) {
        return Results.success(promptConfigService.detail(key));
    }

    /**
     * 更新提示词：写历史（旧版本）→ 版本 +1 → 立即热重载
     */
    @PutMapping("/{key}")
    public Result<PromptConfigVO> update(@PathVariable String key,
                                         @RequestBody PromptConfigUpdateRequest request) {
        promptConfigService.update(key, request);
        return Results.success(promptConfigService.detail(key));
    }

    /**
     * 重置为出厂默认（classpath 模板内容）并热重载
     */
    @PostMapping("/{key}/reset")
    public Result<PromptConfigVO> reset(@PathVariable String key) {
        promptConfigService.reset(key);
        return Results.success(promptConfigService.detail(key));
    }

    /**
     * 变更历史（按版本升序）
     */
    @GetMapping("/{key}/history")
    public Result<List<PromptHistoryVO>> history(@PathVariable String key) {
        return Results.success(promptConfigService.history(key));
    }

    /**
     * 回滚到指定版本（version=1 表示出厂默认）
     */
    @PostMapping("/{key}/history/{version}/rollback")
    public Result<PromptConfigVO> rollback(@PathVariable String key, @PathVariable int version) {
        promptConfigService.rollback(key, version);
        return Results.success(promptConfigService.detail(key));
    }

    /**
     * 试渲染：用给定 slots 填充提示词，编辑时校验占位符与效果
     */
    @PostMapping("/{key}/preview")
    public Result<String> preview(@PathVariable String key, @RequestBody(required = false) Map<String, String> slots) {
        return Results.success(promptConfigService.preview(key, slots));
    }
}