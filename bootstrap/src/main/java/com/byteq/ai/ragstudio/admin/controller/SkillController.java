package com.byteq.ai.ragstudio.admin.controller;

import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SKILL（技能）管理控制器
 * <p>
 * 提供 SKILL 列表查询和手动刷新功能。
 * 仅返回 SKILL 的名称和描述，不暴露内部配置细节。
 * </p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/skills")
public class SkillController {

    private final SkillLoader skillLoader;

    /**
     * 获取所有 SKILL 的摘要列表
     */
    @GetMapping
    public Result<List<Map<String, String>>> listSkills() {
        return Results.success(skillLoader.listSkillSummaries());
    }

    /**
     * 手动重新扫描 skills 目录并刷新到 Redis
     */
    @PostMapping("/reload")
    public Result<Void> reloadSkills() {
        log.info("手动触发 SKILL 重新加载");
        skillLoader.scanAndLoad();
        return Results.success();
    }
}
