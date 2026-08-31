package com.byteq.ai.ragstudio.rag.skillstore;

import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDirs;
import com.byteq.ai.ragstudio.rag.core.skill.SkillIssue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SKILL 存储核心逻辑冒烟测试：校验器、包读取（zip-slip/根目录剥离/隐藏文件）、树 hash 一致性、树级 diff。
 */
class SkillStoreCoreTest {

    private static final String SKILL_MD = """
            ---
            name: demo
            description: 演示技能。当用户提到演示时使用。
            metadata:
              version: "1.2.0"
            ---

            # Demo
            """;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ==================== SkillValidator ====================

    @Test
    void validateValidScriptSkill() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", bytes(SKILL_MD));
        files.put("skill.yaml", bytes("""
                type: script
                config:
                  scriptFile: "run.py"
                  interpreter: "python3"
                parameters:
                  type: object
                  properties:
                    q:
                      type: string
                  required:
                    - q
                """));
        files.put("scripts/run.py", bytes("print('hello')\n"));

        SkillValidator.ValidatedSkill v = SkillValidator.validate("demo", files);
        assertFalse(v.hasError(), () -> "不应有 ERROR: " + v.errors());
        assertEquals("demo", v.name());
        assertEquals("script", v.skillType());
        assertEquals("1.2.0", v.declaredVersion());
        assertEquals(Boolean.TRUE, v.textFlags().get("scripts/run.py"));
        assertEquals(List.of("run.py"), v.manifest().get("scriptFiles"));
    }

    @Test
    void validateMissingSkillMd() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("skill.yaml", bytes("type: http\nconfig:\n  url: \"https://x\"\n"));
        SkillValidator.ValidatedSkill v = SkillValidator.validate(null, files);
        assertTrue(v.hasError());
        assertTrue(v.errors().stream().anyMatch(i -> "NO_SKILL_MD".equals(i.code())));
    }

    @Test
    void validateNameMismatch() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", bytes(SKILL_MD));
        SkillValidator.ValidatedSkill v = SkillValidator.validate("other-name", files);
        assertTrue(v.hasError());
        assertTrue(v.errors().stream().anyMatch(i -> "NAME_MISMATCH".equals(i.code())));
    }

    @Test
    void validateCommandWithoutCommandConfig() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", bytes(SKILL_MD));
        files.put("skill.yaml", bytes("""
                type: command
                config:
                  timeout: 5
                """));
        SkillValidator.ValidatedSkill v = SkillValidator.validate(null, files);
        assertTrue(v.hasError());
        assertTrue(v.errors().stream().anyMatch(i -> "COMMAND_MISSING".equals(i.code())));
    }

    @Test
    void validateScriptFileMissing() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", bytes(SKILL_MD));
        files.put("skill.yaml", bytes("""
                type: script
                config:
                  scriptFile: "not_exists.py"
                """));
        SkillValidator.ValidatedSkill v = SkillValidator.validate(null, files);
        assertTrue(v.hasError());
        assertTrue(v.errors().stream().anyMatch(i -> "SCRIPT_FILE_MISSING".equals(i.code())));
    }

    @Test
    void textClassification() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("data/geo.json", bytes("{\"a\":1}"));
        files.put("data/blob.bin", new byte[]{'a', 0x00, 'b'});
        Map<String, Boolean> flags = SkillValidator.validate(null, Map.of("SKILL.md", bytes(SKILL_MD))).textFlags();
        // 直接走 isText 包内方法验证分类规则
        assertTrue(SkillValidator.isText("data/geo.json", bytes("{\"a\":1}")));
        assertFalse(SkillValidator.isText("data/blob.bin", new byte[]{'a', 0x00, 'b'}));
        assertNotNull(flags);
    }

    // ==================== SkillPackageService ====================

    @Test
    void normalizeEntryPathRules() {
        assertNull(SkillPackageService.normalizeEntryPath("../evil.txt"));
        assertNull(SkillPackageService.normalizeEntryPath("a/../../b"));
        assertNull(SkillPackageService.normalizeEntryPath("/abs/path"));
        assertNull(SkillPackageService.normalizeEntryPath("C:/win/path"));
        assertNull(SkillPackageService.normalizeEntryPath("a//b"));
        assertNull(SkillPackageService.normalizeEntryPath("./x"));
        assertNull(SkillPackageService.normalizeEntryPath(""));
        assertEquals("scripts/a.py", SkillPackageService.normalizeEntryPath("scripts\\a.py"));
        // 隐藏文件不再属于"非法路径"（由读取方跳过），macOS 打包的 .DS_Store 不应导致整包被拒
        assertEquals(".DS_Store", SkillPackageService.normalizeEntryPath(".DS_Store"));
    }

    @Test
    void readZipStripsRootAndSkipsHidden() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("demo/SKILL.md"));
            zos.write(bytes(SKILL_MD));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("demo/scripts/run.sh"));
            zos.write(bytes("echo hi\n"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("demo/.DS_Store"));
            zos.write(bytes("junk"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("__MACOSX/demo/._SKILL.md"));
            zos.write(bytes("junk"));
            zos.closeEntry();
        }
        SkillPackageService service = new SkillPackageService("1MB", "2MB");
        try (InputStream in = new ByteArrayInputStream(bos.toByteArray())) {
            LinkedHashMap<String, byte[]> files = service.readZipStream(in);
            assertEquals(2, files.size());
            assertTrue(files.containsKey("SKILL.md"));
            assertTrue(files.containsKey("scripts/run.sh"));
        }
    }

    @Test
    void readZipRejectsZipSlip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("../evil.txt"));
            zos.write(bytes("evil"));
            zos.closeEntry();
        }
        SkillPackageService service = new SkillPackageService("1MB", "2MB");
        try (InputStream in = new ByteArrayInputStream(bos.toByteArray())) {
            assertThrows(ClientException.class, () -> service.readZipStream(in));
        }
    }

    @Test
    void readDirSkipsHidden(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("SKILL.md"), SKILL_MD);
        Files.createDirectories(tempDir.resolve("scripts"));
        Files.writeString(tempDir.resolve("scripts/a.sh"), "echo hi\n");
        Files.writeString(tempDir.resolve(".DS_Store"), "junk");
        SkillPackageService service = new SkillPackageService("1MB", "2MB");
        Map<String, byte[]> files = service.readDir(tempDir);
        assertEquals(2, files.size());
        assertTrue(files.containsKey("SKILL.md"));
        assertTrue(files.containsKey("scripts/a.sh"));
    }

    @Test
    void readZipRejectsOversizedEntry() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("big.bin"));
            zos.write(new byte[2 * 1024]);
            zos.closeEntry();
        }
        SkillPackageService service = new SkillPackageService("1KB", "2MB");
        try (InputStream in = new ByteArrayInputStream(bos.toByteArray())) {
            assertThrows(ClientException.class, () -> service.readZipStream(in));
        }
    }

    @Test
    void readZipRejectsOversizedTotal() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("a.bin"));
            zos.write(new byte[900]);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("b.bin"));
            zos.write(new byte[900]);
            zos.closeEntry();
        }
        // 单文件 1KB 均未超限，总大小超 1KB 上限
        SkillPackageService service = new SkillPackageService("1KB", "1KB");
        try (InputStream in = new ByteArrayInputStream(bos.toByteArray())) {
            assertThrows(ClientException.class, () -> service.readZipStream(in));
        }
    }

    @Test
    void readDirRejectsOversizedFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("SKILL.md"), SKILL_MD);
        Files.write(tempDir.resolve("big.bin"), new byte[2 * 1024]);
        SkillPackageService service = new SkillPackageService("1KB", "2MB");
        assertThrows(ClientException.class, () -> service.readDir(tempDir));
    }

    // ==================== SkillDirs.treeHash 一致性 ====================

    /** 物化完整性校验依赖"内存文件集"与"磁盘目录"算出的树 hash 完全一致 */
    @Test
    void treeHashMapMatchesDir(@TempDir Path tempDir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", bytes(SKILL_MD));
        files.put("scripts/run.sh", bytes("echo hi\n"));
        files.put("scripts/data.json", bytes("{\"k\":1}"));

        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Path file = tempDir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue());
        }
        String mapHash = SkillDirs.treeHash(files);
        String dirHash = SkillDirs.treeHashOfDir(tempDir);
        assertEquals(mapHash, dirHash);
        // 多余文件会导致 hash 变化（漂移检测依据）
        Files.writeString(tempDir.resolve("extra.txt"), "x");
        SkillDirs.treeHashOfDir(tempDir);
        assertFalse(mapHash.equals(SkillDirs.treeHashOfDir(tempDir)));
    }

    // ==================== SkillDiffService ====================

    @Test
    void treeDiffSetOperations() {
        SkillDiffService service = new SkillDiffService();
        List<SkillDiffService.FileMeta> from = List.of(
                new SkillDiffService.FileMeta("SKILL.md", "h1", 10L, false),
                new SkillDiffService.FileMeta("scripts/old.sh", "h2", 20L, false),
                new SkillDiffService.FileMeta("scripts/a.py", "h3", 30L, false));
        List<SkillDiffService.FileMeta> to = List.of(
                new SkillDiffService.FileMeta("SKILL.md", "h1", 10L, false),
                new SkillDiffService.FileMeta("scripts/a.py", "h3x", 35L, false),
                new SkillDiffService.FileMeta("references/new.md", "h4", 5L, false));

        SkillDiffService.DiffResult result = service.diff(2, 3, null, null, from, to);
        assertEquals(1, result.added());
        assertEquals(1, result.deleted());
        assertEquals(1, result.modified());
        assertEquals(1, result.unchanged());
        assertEquals("modified", statusOf(result, "scripts/a.py"));
        assertEquals("added", statusOf(result, "references/new.md"));
        assertEquals("deleted", statusOf(result, "scripts/old.sh"));
        assertEquals("unchanged", statusOf(result, "SKILL.md"));
        assertEquals(List.of("元信息无变更"), result.manifestChanges());
    }

    @Test
    void manifestChangesDetected() {
        SkillDiffService service = new SkillDiffService();
        String from = """
                {"name":"demo","description":"旧描述","type":null,"config":null,"parameters":null}""";
        String to = """
                {"name":"demo","description":"新描述","type":"script","config":{"scriptFile":"a.py"},"parameters":{"q":{}}}""";
        List<String> changes = service.manifestChanges(from, to);
        assertTrue(changes.stream().anyMatch(c -> c.contains("description")));
        assertTrue(changes.stream().anyMatch(c -> c.contains("type")));
        assertTrue(changes.stream().anyMatch(c -> c.contains("config")));
        assertTrue(changes.stream().anyMatch(c -> c.contains("parameters")));
    }

    private static String statusOf(SkillDiffService.DiffResult result, String path) {
        return result.files().stream()
                .filter(f -> f.path().equals(path))
                .map(SkillDiffService.DiffFile::status)
                .findFirst()
                .orElseThrow();
    }
}
