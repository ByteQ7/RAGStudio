package com.byteq.ai.ragstudio.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.skillstore.SkillAdminService;
import com.byteq.ai.ragstudio.rag.skillstore.SkillBlankInput;
import com.byteq.ai.ragstudio.rag.skillstore.SkillCommitInput;
import com.byteq.ai.ragstudio.rag.skillstore.SkillDetail;
import com.byteq.ai.ragstudio.rag.skillstore.SkillDiffService;
import com.byteq.ai.ragstudio.rag.skillstore.SkillFileContent;
import com.byteq.ai.ragstudio.rag.skillstore.SkillListItem;
import com.byteq.ai.ragstudio.rag.skillstore.SkillVersionInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * SKILL（技能）管理控制器
 * <p>
 * DB 事实源 + 版本化 + 工作区物化的管理面（见 docs/skill-management-design.md）。
 * 运行链路（Agent 工具注册、沙箱执行）不经过本控制器。</p>
 */
@Slf4j
@RestController
@SaCheckRole("admin")
@RequiredArgsConstructor
@RequestMapping("/admin/skills")
public class SkillController {

    private final SkillLoader skillLoader;
    private final SkillAdminService skillAdminService;

    // ==================== 查询 ====================

    /** 技能列表（DB + 运行时状态合并，含待同步/漂移/未入库标记） */
    @GetMapping
    public Result<List<SkillListItem>> listSkills() {
        return Results.success(skillAdminService.list());
    }

    /** 技能详情（当前版本 manifest + 文件树 + 运行时状态） */
    @GetMapping("/{name}")
    public Result<SkillDetail> detail(@PathVariable String name) {
        return Results.success(skillAdminService.detail(name));
    }

    /** 版本历史列表 */
    @GetMapping("/{name}/versions")
    public Result<List<SkillVersionInfo>> versions(@PathVariable String name) {
        return Results.success(skillAdminService.versions(name));
    }

    /** 指定版本文件树 */
    @GetMapping("/{name}/versions/{version}/tree")
    public Result<List<SkillListItem.FileEntry>> versionTree(@PathVariable String name,
                                                             @PathVariable int version) {
        return Results.success(skillAdminService.versionTree(name, version));
    }

    /** 指定版本文本文件内容（在线编辑与版本 diff 取内容用） */
    @GetMapping("/{name}/versions/{version}/file")
    public Result<SkillFileContent> versionFile(@PathVariable String name,
                                                @PathVariable int version,
                                                @RequestParam String path) {
        return Results.success(skillAdminService.versionFile(name, version, path));
    }

    /** 树级 diff（任意两个版本） */
    @GetMapping("/{name}/diff")
    public Result<SkillDiffService.DiffResult> diff(@PathVariable String name,
                                                    @RequestParam int from,
                                                    @RequestParam int to) {
        return Results.success(skillAdminService.diff(name, from, to));
    }

    // ==================== 创建与编辑 ====================

    /** 空白模板新建（服务端生成 SKILL.md 骨架）→ v1 */
    @PostMapping("/blank")
    public Result<SkillDetail> createBlank(@RequestBody SkillBlankInput input) {
        return Results.success(skillAdminService.createBlank(input, UserContext.getUsername()));
    }

    /** ZIP 包新建技能 → v1 */
    @PostMapping
    public Result<SkillDetail> createFromZip(@RequestParam("file") MultipartFile file) {
        return Results.success(skillAdminService.createFromZip(file, UserContext.getUsername()));
    }

    /** 在线编辑提交（upserts + deletions 一次提交 = 一个新版本） */
    @PostMapping("/{name}/commit")
    public Result<SkillDetail> commit(@PathVariable String name, @RequestBody SkillCommitInput input) {
        return Results.success(skillAdminService.commit(name, input, UserContext.getUsername()));
    }

    /** ZIP 上传新版本 → vN+1 */
    @PostMapping("/{name}/versions")
    public Result<SkillDetail> uploadVersion(@PathVariable String name,
                                             @RequestParam("file") MultipartFile file) {
        return Results.success(skillAdminService.uploadVersion(name, file, UserContext.getUsername()));
    }

    /** ZIP 上传 dry-run：只校验并返回树级 diff 预览，不入库 */
    @PostMapping("/{name}/versions/preview")
    public Result<SkillDiffService.DiffResult> uploadVersionPreview(@PathVariable String name,
                                                                    @RequestParam("file") MultipartFile file) {
        return Results.success(skillAdminService.uploadVersionPreview(name, file));
    }

    /** 回滚到指定版本（以旧版本内容生成新版本并生效） */
    @PostMapping("/{name}/rollback/{version}")
    public Result<SkillDetail> rollback(@PathVariable String name, @PathVariable int version) {
        return Results.success(skillAdminService.rollback(name, version, UserContext.getUsername()));
    }

    // ==================== 状态与运维 ====================

    /** 停用：DB 标记 + 从工作区移除 + 运行时卸载 */
    @PostMapping("/{name}/disable")
    public Result<SkillDetail> disable(@PathVariable String name) {
        return Results.success(skillAdminService.disable(name, UserContext.getUsername()));
    }

    /** 启用：物化当前版本 + 运行时加载 */
    @PostMapping("/{name}/enable")
    public Result<SkillDetail> enable(@PathVariable String name) {
        return Results.success(skillAdminService.enable(name, UserContext.getUsername()));
    }

    /** 删除技能（全部版本 + 工作区目录 + 运行时缓存） */
    @DeleteMapping("/{name}")
    public Result<Void> delete(@PathVariable String name) {
        skillAdminService.delete(name);
        return Results.success();
    }

    /** 收编：把工作区中未入库的技能目录建档为 v1 */
    @PostMapping("/{name}/import")
    public Result<SkillDetail> importSkill(@PathVariable String name) {
        return Results.success(skillAdminService.importFromDir(name, UserContext.getUsername()));
    }

    /** 以 DB 当前版本重新物化（修复漂移/待同步） */
    @PostMapping("/{name}/sync")
    public Result<SkillDetail> sync(@PathVariable String name) {
        return Results.success(skillAdminService.sync(name));
    }

    /** 手动重新扫描工作区并刷新运行时缓存与 Redis catalog（兼容保留） */
    @PostMapping("/reload")
    public Result<Void> reloadSkills() {
        log.info("手动触发 SKILL 重新加载, operator={}", UserContext.getUsername());
        skillLoader.scanAndLoad();
        return Results.success();
    }
}
