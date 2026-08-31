package com.byteq.ai.ragstudio.rag.skillstore;

import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDirs;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillVersionDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.mapper.SkillMapper;
import com.byteq.ai.ragstudio.rag.skillstore.dao.mapper.SkillVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL 工作区服务：把 DB 中的当前生效版本物化（materialize）为磁盘目录
 * <p>
 * DB 是唯一事实源，磁盘目录是运行时工作区——沙箱执行（Docker 卷挂载 scripts/:ro）
 * 与 {@link com.byteq.ai.ragstudio.rag.core.skill.SkillLoader} 都从这里读文件。
 * 写入采用 staging → trash → 原子替换流程，运行时永远不会看到"半写"目录。</p>
 */
@Slf4j
@Service
public class SkillWorkspaceService {

    private static final String STAGING_DIR = ".staging";
    private static final String TRASH_DIR = ".trash";

    private final Path skillsDir;
    private final SkillMapper skillMapper;
    private final SkillVersionMapper versionMapper;
    private final SkillStorageService storageService;
    private final SkillPackageService packageService;

    public SkillWorkspaceService(
            @Value("${rag.skills.dir:skills}") String skillsDirPath,
            SkillMapper skillMapper,
            SkillVersionMapper versionMapper,
            SkillStorageService storageService,
            SkillPackageService packageService) {
        this.skillsDir = SkillDirs.resolve(skillsDirPath);
        this.skillMapper = skillMapper;
        this.versionMapper = versionMapper;
        this.storageService = storageService;
        this.packageService = packageService;
    }

    public Path getSkillsDir() {
        return skillsDir;
    }

    // ==================== 启动对账 ====================

    /**
     * 启动对账：以 DB 为准修复工作区（DB 不可用时由调用方捕获并跳过，工作区保持现状继续服务）。
     * DB 无任何技能且旧项目目录 skills/ 存在时，执行一次性自动收编（升级迁移）。
     */
    public void reconcile() {
        cleanupTransientDirs();
        List<SkillDO> all = storageService.listAll();
        if (all.isEmpty()) {
            importLegacyDir();
            return;
        }
        for (SkillDO skill : all) {
            try {
                Path target = skillsDir.resolve(skill.getName());
                if (!Boolean.TRUE.equals(skill.getEnabled())) {
                    removeQuietly(target);
                    continue;
                }
                SkillVersionDO current = storageService.getVersion(skill.getId(), skill.getCurrentVersion());
                if (current == null) {
                    log.warn("SKILL [{}] 当前版本 v{} 缺失，跳过对账", skill.getName(), skill.getCurrentVersion());
                    continue;
                }
                String dirHash = SkillDirs.treeHashOfDir(target);
                if (dirHash != null && dirHash.equals(current.getTreeHash())) {
                    continue;
                }
                log.info("SKILL [{}] 工作区与 DB 不一致（dir={}，期望 v{}），重新物化",
                        skill.getName(), dirHash == null ? "缺失" : "内容不同", skill.getCurrentVersion());
                materialize(skill, current);
            } catch (Exception e) {
                log.warn("SKILL [{}] 对账/物化失败（跳过，可手动同步修复）", skill.getName(), e);
            }
        }
    }

    /**
     * 启动时清理物化临时区与回收站的残留（进程崩溃可能留下未删完的目录）。
     * 此时物化流程尚未并发运行，清理是安全的；单项失败仅告警，不阻断对账。
     */
    private void cleanupTransientDirs() {
        for (String dirName : new String[]{STAGING_DIR, TRASH_DIR}) {
            Path dir = skillsDir.resolve(dirName);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var entries = Files.list(dir)) {
                for (Path entry : entries.toList()) {
                    try {
                        deleteRecursively(entry);
                        log.info("已清理 SKILL {} 残留: {}", dirName, entry.getFileName());
                    } catch (Exception e) {
                        log.warn("清理 SKILL {} 残留失败（跳过）: {}", dirName, entry, e);
                    }
                }
            } catch (IOException e) {
                log.warn("扫描 SKILL {} 失败（跳过清理）", dirName, e);
            }
        }
    }

    /** 一次性收编：旧项目根目录 skills/ 下的技能导入 DB 并物化到新工作区 */
    private void importLegacyDir() {
        Path legacy = resolveLegacyDir();
        if (legacy == null) {
            return;
        }
        log.info("检测到存量 SKILL 目录 {}，开始自动收编（一次性迁移）", legacy.toAbsolutePath());
        try (var dirs = Files.list(legacy)) {
            for (Path dir : dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList()) {
                try {
                    LinkedHashMap<String, byte[]> files = packageService.readDir(dir);
                    String dirName = dir.getFileName().toString();
                    SkillValidator.ValidatedSkill validated = SkillValidator.validate(null, files);
                    if (validated.hasError()) {
                        log.warn("存量 SKILL [{}] 校验未通过，已跳过: {}", dirName,
                                validated.errors().stream().map(i -> i.code() + ": " + i.message()).toList());
                        continue;
                    }
                    SkillDO saved = storageService.saveNewVersion(null, validated, files,
                            "从目录自动导入（升级迁移）", "system");
                    materialize(saved, storageService.getCurrentVersion(saved));
                    log.info("存量 SKILL [{}] 已收编为 DB v{}", dirName, saved.getCurrentVersion());
                } catch (Exception e) {
                    log.warn("存量 SKILL 目录收编失败（已跳过）: {}", dir, e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描存量 SKILL 目录失败: {}", legacy, e);
        }
    }

    /** 旧目录定位：项目根 skills/（原 rag.skills.dir 默认值），工作区自身除外 */
    private Path resolveLegacyDir() {
        Path userDir = Path.of(System.getProperty("user.dir"));
        List<Path> candidates = List.of(
                userDir.resolve("skills"),
                userDir.getParent() == null ? userDir : userDir.getParent().resolve("skills"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate) && !candidate.equals(skillsDir)) {
                return candidate;
            }
        }
        return null;
    }

    // ==================== 物化 ====================

    /**
     * 物化某技能的当前生效版本到工作区（staging 写出 → 全树校验 → 原子替换）。
     * 失败不抛出到调用方写路径之外：DB 已提交为准，可通过再次 sync/启动对账修复。
     */
    public synchronized void materialize(SkillDO skill) {
        SkillVersionDO version = storageService.getCurrentVersion(skill);
        materialize(skill, version);
    }

    private void materialize(SkillDO skill, SkillVersionDO version) {
        Path target = skillsDir.resolve(skill.getName());
        Path staging = skillsDir.resolve(STAGING_DIR).resolve(
                skill.getName() + ".v" + version.getVersion() + "." + System.currentTimeMillis());
        Path trash = skillsDir.resolve(TRASH_DIR).resolve(
                skill.getName() + "." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
        try {
            LinkedHashMap<String, byte[]> files = storageService.loadVersionFiles(version);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path file = staging.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, e.getValue());
            }
            String stagingHash = SkillDirs.treeHashOfDir(staging);
            if (!version.getTreeHash().equals(stagingHash)) {
                throw new ClientException("物化完整性校验失败（staging 与版本 tree_hash 不一致）");
            }
            if (Files.exists(target)) {
                Files.createDirectories(trash.getParent());
                Files.move(target, trash);
            }
            Files.createDirectories(staging.getParent());
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            deleteRecursively(trash);
            storageService.markSynced(skill.getId(), version.getVersion());
            log.info("SKILL [{}] 已物化 v{} → {}", skill.getName(), version.getVersion(),
                    target.toAbsolutePath());
        } catch (Exception e) {
            // 失败恢复：staging 未就位而 target 已移走时还原
            try {
                if (!Files.exists(target) && Files.exists(trash)) {
                    Files.move(trash, target);
                }
            } catch (IOException restoreError) {
                log.error("SKILL [{}] 物化失败且还原失败，需手动同步", skill.getName(), restoreError);
            }
            try {
                deleteRecursively(staging);
            } catch (Exception ignore) {
                // 清理失败留给下次 reconcile
            }
            throw new ClientException("SKILL [" + skill.getName() + "] 物化失败: " + e.getMessage());
        }
    }

    /** 从工作区移除技能目录（停用/删除时），目录不存在则静默返回 */
    public synchronized void remove(String name) {
        removeQuietly(skillsDir.resolve(name));
    }

    private void removeQuietly(Path target) {
        try {
            if (Files.exists(target)) {
                Path trash = skillsDir.resolve(TRASH_DIR).resolve(
                        target.getFileName() + "." + System.currentTimeMillis());
                Files.createDirectories(trash.getParent());
                Files.move(target, trash);
                deleteRecursively(trash);
                log.info("SKILL 目录已从工作区移除: {}", target.getFileName());
            }
        } catch (IOException e) {
            throw new ClientException("移除 SKILL 目录失败: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new IllegalStateException("删除失败: " + p, e);
                }
            });
        }
    }
}
