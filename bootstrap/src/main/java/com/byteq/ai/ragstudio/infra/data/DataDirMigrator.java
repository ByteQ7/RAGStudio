package com.byteq.ai.ragstudio.infra.data;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据目录启动迁移器
 * <p>
 * 把历史上散落各处（相对 cwd / 项目根）的可变数据一次性搬到当前配置的运行位置（默认在 RAGStudioData 下），
 * 幂等可重放。目标一律取**运行时实际配置**的路径（用户显式配置 SKILLS_DIR 等时迁移跟随配置）：
 * <ul>
 *   <li>{@code data/skills}、{@code bootstrap/data/skills}（过渡版本的 SKILL 工作区）→ {@code rag.skills.dir}
 *       ——整体移动后目录内容与 DB tree_hash 一致，reconcile 无需重新物化</li>
 *   <li>{@code logs/rag-search} → {@code rag.search.audit-log.log-dir}（检索审计日志）</li>
 *   <li>{@code AILog} → {@code <数据目录>/AILog}（AI 对话日志）</li>
 *   <li>项目根 {@code resources/models/bge-small-zh-v1.5} → {@code rag.search.crop.model-path}
 *       （语义裁剪模型，JAR 不含 resources）</li>
 * </ul>
 * 迁移策略：源存在且非空、目标不存在或为空时整体移动；移动失败（如跨盘）降级为告警并保留原目录，不阻断启动。
 * 注意：旧项目根 {@code skills/} 目录不在此迁移——由 SKILL 存储的"自动收编"流程入 DB 后物化到新位置。
 * </p>
 */
@Slf4j
@Component
@Order(0)
public class DataDirMigrator implements ApplicationRunner {

    private static final String CROP_MODEL_NAME = "bge-small-zh-v1.5";

    @Value("${rag.skills.dir}")
    private String skillsDir;

    @Value("${rag.search.audit-log.log-dir}")
    private String auditLogDir;

    @Value("${rag.search.crop.model-path}")
    private String cropModelPath;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Path projectRoot = DataDirs.findProjectRoot();

            // SKILL 工作区（过渡版本相对 cwd 的 data/skills）
            Map<Path, Path> skillSources = new LinkedHashMap<>();
            addDistinct(skillSources, Path.of("data", "skills"));
            addDistinct(skillSources, projectRoot == null ? null : projectRoot.resolve("data").resolve("skills"));
            addDistinct(skillSources, projectRoot == null ? null
                    : projectRoot.resolve("bootstrap").resolve("data").resolve("skills"));
            for (Path source : skillSources.keySet()) {
                migrateDir(source, Path.of(skillsDir));
            }

            // 检索审计日志
            Map<Path, Path> auditSources = new LinkedHashMap<>();
            addDistinct(auditSources, Path.of("logs", "rag-search"));
            addDistinct(auditSources, projectRoot == null ? null : projectRoot.resolve("logs").resolve("rag-search"));
            for (Path source : auditSources.keySet()) {
                migrateDir(source, Path.of(auditLogDir));
            }

            // AI 对话日志（历史上硬编码相对 cwd 的 AILog/）
            Map<Path, Path> aiLogSources = new LinkedHashMap<>();
            addDistinct(aiLogSources, Path.of("AILog"));
            addDistinct(aiLogSources, projectRoot == null ? null : projectRoot.resolve("AILog"));
            for (Path source : aiLogSources.keySet()) {
                migrateDir(source, DataDirs.getDataDir().resolve("AILog"));
            }

            // 语义裁剪模型（仓库内 → 数据目录，JAR 部署不含 resources/）
            if (projectRoot != null) {
                migrateDirIfTargetAbsent(
                        projectRoot.resolve("resources").resolve("models").resolve(CROP_MODEL_NAME),
                        Path.of(cropModelPath));
            }

            // 本地文件摄取的规范白名单位置（新建空目录，无历史迁移）
            Files.createDirectories(DataDirs.getDataDir().resolve("uploads"));
        } catch (Exception e) {
            log.warn("数据目录迁移失败（跳过，应用继续启动；旧目录保留原位）", e);
        }
    }

    private static void addDistinct(Map<Path, Path> map, Path path) {
        if (path != null) {
            map.putIfAbsent(path.toAbsolutePath().normalize(), path);
        }
    }

    /** 源存在且非空、目标不存在或为空 → 整体移动；源与目标相同则跳过 */
    static void migrateDir(Path source, Path target) throws IOException {
        Path src = source.toAbsolutePath().normalize();
        Path dst = target.toAbsolutePath().normalize();
        if (src.equals(dst) || !DataDirs.hasChildren(src) || DataDirs.hasChildren(dst)) {
            return;
        }
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst);
            log.info("数据目录迁移完成: {} → {}", src, dst);
        } catch (IOException e) {
            log.warn("数据目录迁移失败（保留原目录，可手动移动）: {} → {}: {}", src, dst, e.getMessage());
        }
    }

    /** 模型目录整目录搬移：目标已存在则不合并（保留现状） */
    static void migrateDirIfTargetAbsent(Path source, Path target) throws IOException {
        Path src = source.toAbsolutePath().normalize();
        Path dst = target.toAbsolutePath().normalize();
        if (src.equals(dst) || !Files.isDirectory(src) || Files.exists(dst)) {
            return;
        }
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst);
            log.info("模型目录迁移完成: {} → {}", src, dst);
        } catch (IOException e) {
            log.warn("模型目录迁移失败（保留原目录）: {} → {}: {}", src, dst, e.getMessage());
        }
    }
}
