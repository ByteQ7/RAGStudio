package com.byteq.ai.ragstudio.rag.skillstore;

import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONUtil;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDirs;
import com.byteq.ai.ragstudio.rag.core.skill.SkillIssue;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.core.skill.SkillMetadata;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillFileDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillVersionDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SKILL 管理编排层：校验 → 存储（DB 事务）→ 物化（磁盘）→ 热重载（SkillLoader 重扫）
 * <p>
 * 写路径顺序遵循"DB 为唯一事实源"：事务提交成功后再物化；物化失败不回滚 DB，
 * 抛出带"待同步"提示的异常，由管理员通过 sync/启动对账修复。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillAdminService {

    /** 文本文件内容读取上限（编辑/对比取内容用；超过则不返回内容） */
    private static final long MAX_TEXT_CONTENT_BYTES = 1024 * 1024;

    private final SkillStorageService storage;
    private final SkillWorkspaceService workspace;
    private final SkillPackageService packages;
    private final SkillDiffService diffService;
    private final SkillLoader skillLoader;

    // ==================== 查询 ====================

    /** 技能列表：DB 记录 + 工作区漂移检测 + 运行时加载状态合并；DB 不可用时降级为纯运行时数据 */
    public List<SkillListItem> list() {
        Map<String, Map<String, String>> runtime = runtimeByName();
        List<SkillListItem> items = new ArrayList<>();
        try {
            // 先组装到临时列表：读取中途失败时整体降级，避免"半截 DB 列表 + 运行时列表"出现重复项
            List<SkillListItem> dbItems = new ArrayList<>();
            for (SkillDO skill : storage.listAll()) {
                dbItems.add(toListItem(skill, runtime.remove(skill.getName())));
            }
            items.addAll(dbItems);
        } catch (Exception e) {
            log.warn("SKILL 列表读取 DB 失败，降级为运行时数据", e);
        }
        // 未入库但工作区/运行时存在的技能
        for (Map.Entry<String, Map<String, String>> e : runtime.entrySet()) {
            Map<String, String> s = e.getValue();
            items.add(new SkillListItem(e.getKey(), s.getOrDefault("description", ""), s.get("type"),
                    null, blankToNull(s.get("version")), null, null, null, null,
                    SyncState.UNMANAGED, boolOf(s.get("loaded")), s.get("errors"), s.get("warnings")));
        }
        return items;
    }

    public SkillDetail detail(String name) {
        SkillDO skill = requireSkill(name);
        SkillVersionDO version = storage.getCurrentVersion(skill);
        Map<String, Object> manifest = parseManifest(version.getManifest());
        Map<String, String> runtime = runtimeByName().getOrDefault(name, Map.of());
        return new SkillDetail(skill.getName(), skill.getDescription(), skill.getSkillType(),
                skill.getCurrentVersion(), declaredVersion(manifest), skill.getEnabled(),
                skill.getChangeLog(), skill.getUpdatedBy(), formatDate(skill.getUpdateTime()),
                syncStateOf(skill, version), manifest, fileEntries(version.getId()),
                boolOf(runtime.get("loaded")), runtime.getOrDefault("errors", ""), runtime.getOrDefault("warnings", ""));
    }

    public List<SkillVersionInfo> versions(String name) {
        SkillDO skill = requireSkill(name);
        return storage.listVersions(skill.getId()).stream()
                .map(v -> new SkillVersionInfo(v.getVersion(), v.getChangeLog(), v.getFileCount(),
                        v.getTotalSize(), v.getCreatedBy(), formatDate(v.getCreateTime()),
                        v.getVersion().equals(skill.getCurrentVersion())))
                .toList();
    }

    public List<SkillListItem.FileEntry> versionTree(String name, int version) {
        SkillDO skill = requireSkill(name);
        return fileEntries(requireVersion(skill, version).getId());
    }

    /** 某版本某文本文件内容（供在线编辑与版本 diff；二进制/超大文件拒绝返回内容） */
    public SkillFileContent versionFile(String name, int version, String path) {
        SkillDO skill = requireSkill(name);
        SkillVersionDO v = requireVersion(skill, version);
        SkillFileDO file = storage.listFiles(v.getId()).stream()
                .filter(f -> f.getFilePath().equals(path))
                .findFirst()
                .orElseThrow(() -> new ClientException("文件不存在于 v" + version + ": " + path));
        if (Boolean.TRUE.equals(file.getIsBinary())) {
            throw new ClientException("二进制文件不支持内容查看/编辑: " + path);
        }
        if (file.getSize() > MAX_TEXT_CONTENT_BYTES) {
            throw new ClientException("文本文件超过 1MB，不支持在线查看/对比: " + path);
        }
        byte[] content = storage.readVersionFile(v, path);
        if (content == null) {
            throw new ClientException("文件内容缺失（blob 记录不存在），请重新上传该版本");
        }
        return new SkillFileContent(path, false, (long) content.length, new String(content, StandardCharsets.UTF_8));
    }

    /** 树级 diff（任意两个版本） */
    public SkillDiffService.DiffResult diff(String name, int from, int to) {
        SkillDO skill = requireSkill(name);
        SkillVersionDO fromV = requireVersion(skill, from);
        SkillVersionDO toV = requireVersion(skill, to);
        return diffService.diff(fromV, toV, storage.listFiles(fromV.getId()), storage.listFiles(toV.getId()));
    }

    // ==================== 写路径 ====================

    public SkillDetail createBlank(SkillBlankInput input, String operator) {
        String name = input.name() == null ? "" : input.name().trim();
        String description = input.description() == null ? "" : input.description().trim();
        if (!name.matches(SkillMetadata.NAME_PATTERN)) {
            throw new ClientException("name 仅允许小写字母/数字/连字符（不以连字符开头结尾）: " + name);
        }
        if (description.isBlank()) {
            throw new ClientException("description 不能为空（注入 System Prompt，供 LLM 判断何时触发）");
        }
        if (storage.getByName(name) != null) {
            throw new ClientException("SKILL 已存在: " + name);
        }
        guardWorkspaceDirNotUnmanaged(name);
        String skillMd = """
                ---
                name: %s
                description: "%s"
                metadata:
                  version: "1"
                ---

                # %s

                ## 功能
                （描述这个技能做什么、什么场景下触发）

                ## 使用说明
                （给 Agent 的指令：如何调用、参数含义、返回格式与注意事项）
                """.formatted(name, escapeYaml(description), name);
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", skillMd.getBytes(StandardCharsets.UTF_8));
        return finishWrite(operator, () -> save(null, name, files, "空白模板创建", operator));
    }

    public SkillDetail createFromZip(MultipartFile file, String operator) {
        LinkedHashMap<String, byte[]> files = packages.readZip(file);
        String zipName = SkillValidator.validate(null, files).name();
        guardWorkspaceDirNotUnmanaged(zipName);
        return finishWrite(operator, () -> saveFromFiles(null, files, "ZIP 包导入", operator));
    }

    /** ZIP 上传新版本（入库并物化生效） */
    public SkillDetail uploadVersion(String name, MultipartFile file, String operator) {
        SkillDO skill = requireSkill(name);
        LinkedHashMap<String, byte[]> files = packages.readZip(file);
        SkillValidator.ValidatedSkill validated = validateOrThrow(name, files);
        SkillVersionDO current = storage.getCurrentVersion(skill);
        if (current.getTreeHash() != null
                && current.getTreeHash().equals(SkillDirs.treeHash(files))) {
            throw new ClientException("上传包与当前版本 v" + current.getVersion() + " 内容一致，无需更新");
        }
        return finishWrite(operator, () -> {
            SkillDO saved = storage.saveNewVersion(skill, validated, files, "ZIP 包更新", operator);
            return detail(saved.getName());
        });
    }

    /** ZIP 上传新版本的 dry-run 预览：只校验并返回树级 diff，不入库 */
    public SkillDiffService.DiffResult uploadVersionPreview(String name, MultipartFile file) {
        SkillDO skill = requireSkill(name);
        LinkedHashMap<String, byte[]> files = packages.readZip(file);
        SkillValidator.ValidatedSkill validated = validateOrThrow(name, files);
        SkillVersionDO current = storage.getCurrentVersion(skill);
        List<SkillDiffService.FileMeta> from = toMeta(storage.listFiles(current.getId()));
        List<SkillDiffService.FileMeta> to = toMeta(files, validated.textFlags());
        return diffService.diff(current.getVersion(), current.getVersion() + 1,
                current.getManifest(), JSONUtil.toJsonStr(validated.manifest()), from, to);
    }

    /** 在线编辑提交：upserts + deletions 应用到当前文件集后保存为新版本（删除最终生效：先应用编辑再删除） */
    public SkillDetail commit(String name, SkillCommitInput input, String operator) {
        SkillDO skill = requireSkill(name);
        LinkedHashMap<String, byte[]> files = storage.loadVersionFiles(storage.getCurrentVersion(skill));

        if (input.upserts() != null) {
            for (Map.Entry<String, String> e : input.upserts().entrySet()) {
                String path = SkillPackageService.normalizeEntryPath(e.getKey());
                if (path == null) {
                    throw new ClientException("文件路径非法: " + e.getKey());
                }
                files.put(path, e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        if (input.deletions() != null) {
            for (String raw : input.deletions()) {
                String path = SkillPackageService.normalizeEntryPath(raw);
                if (path == null || !files.containsKey(path)) {
                    throw new ClientException("删除的文件不存在或路径非法: " + raw);
                }
                files.remove(path);
            }
        }
        packages.enforceLimits(files);
        SkillValidator.ValidatedSkill validated = validateOrThrow(name, files);
        SkillVersionDO current = storage.getCurrentVersion(skill);
        if (current.getTreeHash() != null && current.getTreeHash().equals(SkillDirs.treeHash(files))) {
            throw new ClientException("没有变更，无需保存新版本");
        }
        return finishWrite(operator, () -> {
            SkillDO saved = storage.saveNewVersion(skill, validated, files, changeLogOr(input.changeLog()), operator);
            return detail(saved.getName());
        });
    }

    /** 回滚：以目标版本内容追加新版本（版本号继续递增，语义与提示词管理一致） */
    public SkillDetail rollback(String name, int targetVersion, String operator) {
        SkillDO skill = requireSkill(name);
        SkillVersionDO target = requireVersion(skill, targetVersion);
        SkillVersionDO current = storage.getCurrentVersion(skill);
        if (target.getVersion().equals(current.getVersion())) {
            throw new ClientException("目标版本 v" + targetVersion + " 就是当前版本，无需回滚");
        }
        LinkedHashMap<String, byte[]> files = storage.loadVersionFiles(target);
        if (current.getTreeHash() != null && current.getTreeHash().equals(SkillDirs.treeHash(files))) {
            throw new ClientException("目标版本 v" + targetVersion + " 内容与当前版本一致，无需回滚");
        }
        SkillValidator.ValidatedSkill validated = validateOrThrow(name, files);
        return finishWrite(operator, () -> {
            SkillDO saved = storage.saveNewVersion(skill, validated, files, "回滚自 v" + targetVersion, operator);
            return detail(saved.getName());
        });
    }

    public void delete(String name) {
        storage.delete(name);
        workspace.remove(name);
        skillLoader.scanAndLoad();
        log.info("SKILL [{}] 删除完成（DB/工作区/运行时缓存）", name);
    }

    public SkillDetail enable(String name, String operator) {
        SkillDO skill = storage.setEnabled(name, true);
        materializeOrPending(skill);
        skillLoader.scanAndLoad();
        return detail(name);
    }

    public SkillDetail disable(String name, String operator) {
        SkillDO skill = storage.setEnabled(name, false);
        workspace.remove(name);
        skillLoader.scanAndLoad();
        return detail(name);
    }

    /** 收编：把工作区中未入库的技能目录建档为 v1（frontmatter name 必须与目录名一致） */
    public SkillDetail importFromDir(String name, String operator) {
        if (storage.getByName(name) != null) {
            throw new ClientException("SKILL 已入库: " + name);
        }
        Path dir = resolveWorkspaceDir(name);
        LinkedHashMap<String, byte[]> files = packages.readDir(dir);
        return finishWrite(operator, () -> save(null, name, files, "从工作区目录收编", operator));
    }

    /** 以 DB 当前版本重新物化（修复漂移/待同步） */
    public SkillDetail sync(String name) {
        SkillDO skill = requireSkill(name);
        if (Boolean.TRUE.equals(skill.getEnabled())) {
            workspace.materialize(skill);
        } else {
            workspace.remove(name);
        }
        skillLoader.scanAndLoad();
        return detail(name);
    }

    /** 兼容保留：重扫工作区刷新运行时缓存 */
    public void reload() {
        skillLoader.scanAndLoad();
    }

    // ==================== 内部工具 ====================

    private SkillDetail save(SkillDO current, String expectedName,
                             LinkedHashMap<String, byte[]> files, String changeLog, String operator) {
        SkillValidator.ValidatedSkill validated = validateOrThrow(expectedName, files);
        SkillDO saved = storage.saveNewVersion(current, validated, files, changeLog, operator);
        return detail(saved.getName());
    }

    private SkillDetail saveFromFiles(SkillDO current, LinkedHashMap<String, byte[]> files,
                                      String changeLog, String operator) {
        return save(current, null, files, changeLog, operator);
    }

    /** 写路径收尾：物化（失败不阻断 DB 结果，但明确提示待同步）→ 热重载 */
    private SkillDetail finishWrite(String operator, java.util.function.Supplier<SkillDetail> action) {
        SkillDetail detail = action.get();
        SkillDO skill = requireSkill(detail.name());
        try {
            materializeOrPending(skill);
        } catch (Exception e) {
            throw new ClientException("版本 v" + detail.currentVersion()
                    + " 已保存，但物化到工作区失败（待同步）: " + e.getMessage());
        }
        skillLoader.scanAndLoad();
        return detail;
    }

    private void materializeOrPending(SkillDO skill) {
        if (Boolean.TRUE.equals(skill.getEnabled())) {
            workspace.materialize(skill);
        } else {
            workspace.remove(skill.getName());
        }
    }

    private SkillValidator.ValidatedSkill validateOrThrow(String expectedName, Map<String, byte[]> files) {
        SkillValidator.ValidatedSkill validated = SkillValidator.validate(expectedName, files);
        if (validated.hasError()) {
            String detail = validated.errors().stream()
                    .map(i -> i.code() + ": " + i.message())
                    .collect(Collectors.joining("；"));
            throw new ClientException("校验未通过：" + detail);
        }
        return validated;
    }

    private SkillDO requireSkill(String name) {
        SkillDO skill = storage.getByName(name);
        if (skill == null) {
            throw new ClientException("SKILL 未入库或不存在: " + name);
        }
        return skill;
    }

    /** 新建防覆盖：工作区存在同名未入库目录时，物化会覆盖销毁其内容，应先走收编 */
    private void guardWorkspaceDirNotUnmanaged(String name) {
        if (name == null || storage.getByName(name) != null) {
            return;
        }
        if (Files.isDirectory(workspace.getSkillsDir().resolve(name))) {
            throw new ClientException("工作区已存在同名目录 " + name + "（未入库），请先在列表中「收编」，或删除该目录后再新建");
        }
    }

    private SkillVersionDO requireVersion(SkillDO skill, int version) {
        SkillVersionDO v = storage.getVersion(skill.getId(), version);
        if (v == null) {
            throw new ClientException("版本不存在: " + skill.getName() + " v" + version);
        }
        return v;
    }

    private Path resolveWorkspaceDir(String name) {
        Path dir = workspace.getSkillsDir().resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new ClientException("工作区目录不存在: " + name);
        }
        return dir;
    }

    private List<SkillDiffService.FileMeta> toMeta(List<SkillFileDO> files) {
        return files.stream()
                .map(f -> new SkillDiffService.FileMeta(f.getFilePath(), f.getBlobHash(), f.getSize(), f.getIsBinary()))
                .toList();
    }

    private List<SkillDiffService.FileMeta> toMeta(Map<String, byte[]> files, Map<String, Boolean> textFlags) {
        return files.entrySet().stream()
                .map(e -> new SkillDiffService.FileMeta(e.getKey(),
                        java.util.HexFormat.of().formatHex(SkillDirs.sha256(e.getValue())),
                        (long) e.getValue().length,
                        textFlags.getOrDefault(e.getKey(), true)))
                .toList();
    }

    private SkillListItem toListItem(SkillDO skill, Map<String, String> runtime) {
        SkillVersionDO current = storage.getVersion(skill.getId(), skill.getCurrentVersion());
        Map<String, Object> manifest = current == null ? Map.of() : parseManifest(current.getManifest());
        return new SkillListItem(skill.getName(), skill.getDescription(), skill.getSkillType(),
                skill.getCurrentVersion(), declaredVersion(manifest), skill.getEnabled(),
                skill.getChangeLog(), skill.getUpdatedBy(), formatDate(skill.getUpdateTime()),
                syncStateOf(skill, current), boolOf(runtime == null ? null : runtime.get("loaded")),
                runtime == null ? "" : runtime.getOrDefault("errors", ""),
                runtime == null ? "" : runtime.getOrDefault("warnings", ""));
    }

    private SyncState syncStateOf(SkillDO skill, SkillVersionDO current) {
        Integer synced = skill.getSyncedVersion();
        if (current != null && current.getTreeHash() != null) {
            if (synced == null || synced < skill.getCurrentVersion()) {
                return SyncState.PENDING_SYNC;
            }
            if (Boolean.TRUE.equals(skill.getEnabled())) {
                String dirHash = SkillDirs.treeHashOfDir(workspace.getSkillsDir().resolve(skill.getName()));
                return current.getTreeHash().equals(dirHash) ? SyncState.SYNCED : SyncState.DRIFTED;
            }
            return Files.exists(workspace.getSkillsDir().resolve(skill.getName()))
                    ? SyncState.PENDING_SYNC : SyncState.SYNCED;
        }
        return SyncState.PENDING_SYNC;
    }

    private List<SkillListItem.FileEntry> fileEntries(Long versionId) {
        return storage.listFiles(versionId).stream()
                .map(f -> new SkillListItem.FileEntry(f.getFilePath(), f.getIsBinary(), f.getSize()))
                .toList();
    }

    /** 运行时摘要（SkillLoader 诊断）按名索引 */
    private Map<String, Map<String, String>> runtimeByName() {
        return skillLoader.listSkillSummaries().stream()
                .collect(Collectors.toMap(s -> s.get("name"), s -> s, (a, b) -> a));
    }

    private static Map<String, Object> parseManifest(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String declaredVersion(Map<String, Object> manifest) {
        if (manifest.get("metadata") instanceof Map<?, ?> metadata) {
            Object v = metadata.get("version");
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    private static String formatDate(java.util.Date date) {
        return date == null ? null : DateUtil.format(date, "yyyy-MM-dd HH:mm:ss");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Boolean boolOf(String value) {
        return value == null ? null : Boolean.parseBoolean(value);
    }

    private static String changeLogOr(String changeLog) {
        return changeLog == null || changeLog.isBlank() ? "在线编辑更新" : changeLog.trim();
    }

    private static String escapeYaml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
