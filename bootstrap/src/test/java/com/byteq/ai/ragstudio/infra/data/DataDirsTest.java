package com.byteq.ai.ragstudio.infra.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据目录解析与迁移的单元测试。
 */
class DataDirsTest {

    @TempDir
    Path tempDir;

    private void withUserDir(Path dir, Runnable action) {
        String original = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", dir.toAbsolutePath().toString());
            action.run();
        } finally {
            System.setProperty("user.dir", original);
        }
    }

    // ==================== 项目根探测 ====================

    @Test
    void findProjectRootPrefersTopmostPom() throws IOException {
        // 模拟多模块：a（聚合根，有 pom.xml）/ b（子模块，有 pom.xml），cwd=b → 应解析到 a
        Path root = tempDir.resolve("a");
        Path module = root.resolve("b");
        Files.createDirectories(module);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        withUserDir(module, () -> {
            Path detected = DataDirs.findProjectRoot();
            assertEquals(root.toAbsolutePath().normalize(), detected);
        });
        // IDEA 场景：cwd=聚合根
        withUserDir(root, () -> assertEquals(root.toAbsolutePath().normalize(), DataDirs.findProjectRoot()));
    }

    @Test
    void findProjectRootReturnsNullWhenNoPom() {
        withUserDir(tempDir, () -> assertNull(DataDirs.findProjectRoot()));
    }

    @Test
    void resolveExplicitConfigWins() {
        Path configured = DataDirs.resolve(tempDir.resolve("mydata").toString());
        assertEquals(tempDir.resolve("mydata").toAbsolutePath().normalize(), configured);
    }

    @Test
    void resolveInProjectGoesSibling() throws IOException {
        Path root = tempDir.resolve("RAGStudio");
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        withUserDir(root, () -> {
            Path dataDir = DataDirs.resolve(null);
            assertEquals(root.getParent().resolve("RAGStudioData").normalize(), dataDir);
        });
    }

    // ==================== 迁移移动 ====================

    @Test
    void migrateDirMovesNonEmptySourceToEmptyTarget() throws IOException {
        Path source = tempDir.resolve("old").resolve("skills");
        Path target = tempDir.resolve("new").resolve("skills");
        Files.createDirectories(source.resolve("scripts"));
        Files.writeString(source.resolve("SKILL.md"), "demo");
        Files.writeString(source.resolve("scripts").resolve("a.sh"), "echo hi");

        DataDirMigrator.migrateDir(source, target);

        assertFalse(Files.exists(source));
        assertTrue(Files.isRegularFile(target.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(target.resolve("scripts").resolve("a.sh")));
    }

    @Test
    void migrateDirSkipsWhenTargetOccupiedOrSourceEmpty() throws IOException {
        Path source = tempDir.resolve("src");
        Path target = tempDir.resolve("dst");
        // 源为空：跳过
        Files.createDirectories(source);
        DataDirMigrator.migrateDir(source, target);
        assertTrue(Files.isDirectory(source));
        assertFalse(Files.exists(target));

        // 目标非空：跳过（保留现状）
        Files.writeString(source.resolve("a.txt"), "1");
        Files.createDirectories(target);
        Files.writeString(target.resolve("b.txt"), "2");
        DataDirMigrator.migrateDir(source, target);
        assertTrue(Files.exists(source.resolve("a.txt")));
        assertTrue(Files.exists(target.resolve("b.txt")));
    }

    @Test
    void migrateDirModelOnlyWhenTargetAbsent() throws IOException {
        Path source = tempDir.resolve("models-src").resolve("bge");
        Path target = tempDir.resolve("models-dst").resolve("bge");
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.json"), "{}");

        DataDirMigrator.migrateDirIfTargetAbsent(source, target);
        assertTrue(Files.isRegularFile(target.resolve("config.json")));

        // 目标已存在：不合并，源保留
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.json"), "{}");
        DataDirMigrator.migrateDirIfTargetAbsent(source, target);
        assertTrue(Files.exists(source.resolve("config.json")));
    }

    // ==================== EnvironmentPostProcessor ====================

    @Test
    void postProcessorAddsDataDirPropertyAndLoadsDotEnv(@TempDir Path dotEnvDir) throws IOException {
        Files.writeString(dotEnvDir.resolve(".env"), "DATA_DIRS_TEST_KEY=fromfile\n");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("testExplicit", Map.of("RAGSTUDIO_DATA_DIR", dotEnvDir.toString())));

        new DataDirEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals(dotEnvDir.toAbsolutePath().normalize().toString(),
                environment.getProperty("ragstudio.data-dir"));
        assertEquals("fromfile", environment.getProperty("DATA_DIRS_TEST_KEY"));
    }

    @Test
    void postProcessorDotEnvDoesNotOverrideSystemProperty() throws IOException {
        Path dotEnvDir = tempDir.resolve("envdir");
        Files.createDirectories(dotEnvDir);
        Files.writeString(dotEnvDir.resolve(".env"), "DATA_DIRS_TEST_KEY=fromfile\n");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("testExplicit", Map.of("RAGSTUDIO_DATA_DIR", dotEnvDir.toString())));

        String original = System.getProperty("DATA_DIRS_TEST_KEY");
        System.setProperty("DATA_DIRS_TEST_KEY", "fromsys");
        try {
            new DataDirEnvironmentPostProcessor().postProcessEnvironment(environment, null);
            assertEquals("fromsys", environment.getProperty("DATA_DIRS_TEST_KEY"));
        } finally {
            if (original == null) {
                System.clearProperty("DATA_DIRS_TEST_KEY");
            } else {
                System.setProperty("DATA_DIRS_TEST_KEY", original);
            }
        }
    }
}
