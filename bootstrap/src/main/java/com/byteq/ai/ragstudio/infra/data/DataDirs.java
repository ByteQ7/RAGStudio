package com.byteq.ai.ragstudio.infra.data;

import org.springframework.boot.system.ApplicationHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 运行时数据根目录（RAGStudioData）解析
 * <p>
 * 所有运行时可变数据（SKILL 工作区、检索审计日志、AI 对话日志、本地模型、本地摄取白名单）
 * 统一收敛到与项目同级（或 JAR 旁）的 RAGStudioData 目录下，消除"工作目录不同导致落盘位置漂移"的问题。
 * <p>
 * 解析优先级：
 * <ol>
 *   <li>显式配置（OS 环境变量 / 系统属性 / .env 中的 {@code RAGSTUDIO_DATA_DIR}）</li>
 *   <li>项目内运行（从 cwd 向上找到最外层 pom.xml，兼容 IDEA 与 {@code cd bootstrap && mvn spring-boot:run}）
 *       → {@code <项目根>/../RAGStudioData}（与项目同级）</li>
 *   <li>JAR 运行 → {@code <JAR 所在目录>/RAGStudioData}</li>
 * </ol>
 */
public final class DataDirs {

    /** 显式数据目录配置键（OS 环境变量 / 系统属性 / .env） */
    public static final String DATA_DIR_PROPERTY = "RAGSTUDIO_DATA_DIR";
    /** 暴露给 Spring 配置占位符使用的属性键（由 EnvironmentPostProcessor 写入） */
    public static final String DATA_DIR_SPRING_PROPERTY = "ragstudio.data-dir";

    private static final String DATA_DIR_NAME = "RAGStudioData";

    private static volatile Path cachedDataDir;

    private DataDirs() {}

    /** 获取数据根目录（优先取 EnvironmentPostProcessor 预解析结果，否则懒解析并缓存） */
    public static Path getDataDir() {
        Path dir = cachedDataDir;
        if (dir == null) {
            dir = resolve(null);
            cachedDataDir = dir;
        }
        return dir;
    }

    /** 供 EnvironmentPostProcessor 写入预解析结果 */
    public static void initialize(Path dataDir) {
        cachedDataDir = dataDir.toAbsolutePath().normalize();
    }

    /**
     * 解析数据根目录
     *
     * @param configured 显式配置值，可为 null/空
     */
    public static Path resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        Path projectRoot = findProjectRoot();
        if (projectRoot != null && projectRoot.getParent() != null) {
            return projectRoot.getParent().resolve(DATA_DIR_NAME).normalize();
        }
        return applicationHomeDir().resolve(DATA_DIR_NAME);
    }

    /**
     * 项目根探测：从 user.dir 向上找**最外层**含 pom.xml 的目录。
     * 多模块场景（cwd=bootstrap）会上溯到聚合根，避免把子模块当项目根。
     * JAR 部署（cwd 无 pom.xml）返回 null，走 ApplicationHome 兜底。
     */
    public static Path findProjectRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path topmost = null;
        while (dir != null && Files.isRegularFile(dir.resolve("pom.xml"))) {
            topmost = dir;
            dir = dir.getParent();
        }
        return topmost;
    }

    /** JAR 运行时的应用宿主目录（JAR 所在目录；开发态为 target/classes，仅在非项目场景兜底用） */
    private static Path applicationHomeDir() {
        try {
            return new ApplicationHome(DataDirs.class).getDir().toPath().toAbsolutePath().normalize();
        } catch (Exception e) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
    }

    /** 数据目录下的规范子目录 */
    public static Path resolve(String configured, String subDir) {
        return resolve(configured).resolve(subDir);
    }

    /** 目录非空判断（不存在或为空返回 false） */
    public static boolean hasChildren(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }
}
