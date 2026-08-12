package com.byteq.ai.ragstudio.rag.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMetadataTest {

    private static final String DIR = "pdf-processing";

    // ==================== 标准解析 ====================

    @Test
    void parseStandardFrontmatter() {
        String content = """
                ---
                name: pdf-processing
                description: 提取 PDF 文本。当用户提到 PDF 时使用。
                license: Apache-2.0
                compatibility: Requires Python 3.14+
                metadata:
                  author: example-org
                  version: "1.2.0"
                ---
                # PDF 处理
                ## 使用步骤
                """;

        SkillMetadata meta = SkillMetadata.parse(content, DIR);

        assertFalse(meta.hasError(), () -> "issues: " + meta.getIssues());
        assertEquals("pdf-processing", meta.getName());
        assertEquals("提取 PDF 文本。当用户提到 PDF 时使用。", meta.getDescription());
        assertEquals("Apache-2.0", meta.getLicense());
        assertEquals("Requires Python 3.14+", meta.getCompatibility());
        assertEquals("1.2.0", meta.getVersion());
        assertEquals("example-org", meta.getMetadata().get("author"));
        assertEquals("# PDF 处理\n## 使用步骤", meta.getBody());
    }

    @Test
    void parseMinimalFrontmatter() {
        String content = """
                ---
                name: code-review
                description: Review code changes for quality issues.
                ---
                Body here
                """;

        SkillMetadata meta = SkillMetadata.parse(content, "code-review");

        assertFalse(meta.hasError());
        assertEquals("code-review", meta.getName());
        assertEquals("Body here", meta.getBody());
        assertNull(meta.getVersion());
        assertTrue(meta.getMetadata().isEmpty());
    }

    @Test
    void parseWindowsLineEndings() {
        String content = "---\r\nname: data-analysis\r\ndescription: Analyze datasets.\r\n---\r\nBody\r\n";

        SkillMetadata meta = SkillMetadata.parse(content, "data-analysis");

        assertFalse(meta.hasError());
        assertEquals("data-analysis", meta.getName());
        assertEquals("Body", meta.getBody());
    }

    // ==================== 校验 ====================

    @Test
    void missingNameIsError() {
        String content = """
                ---
                description: No name here.
                ---
                """;

        SkillMetadata meta = SkillMetadata.parse(content, DIR);

        assertTrue(meta.hasError());
        assertTrue(hasIssue(meta, "NAME_MISSING", SkillIssue.Severity.ERROR));
    }

    @Test
    void missingDescriptionIsError() {
        String content = """
                ---
                name: pdf-processing
                ---
                """;

        SkillMetadata meta = SkillMetadata.parse(content, DIR);

        assertTrue(meta.hasError());
        assertTrue(hasIssue(meta, "DESCRIPTION_MISSING", SkillIssue.Severity.ERROR));
    }

    @Test
    void nameDirMismatchIsWarning() {
        String content = """
                ---
                name: pdf-processing
                description: Extract text from PDFs.
                ---
                """;

        SkillMetadata meta = SkillMetadata.parse(content, "other-dir");

        assertFalse(meta.hasError());
        assertTrue(hasIssue(meta, "NAME_DIR_MISMATCH", SkillIssue.Severity.WARN));
    }

    @Test
    void invalidNameFormatIsWarning() {
        String content = """
                ---
                name: web_search
                description: Search the web.
                ---
                """;

        SkillMetadata meta = SkillMetadata.parse(content, "web_search");

        assertFalse(meta.hasError());
        assertTrue(hasIssue(meta, "NAME_INVALID", SkillIssue.Severity.WARN));
    }

    @Test
    void overlongDescriptionIsWarning() {
        String content = "---\nname: pdf-processing\ndescription: " + "x".repeat(1200) + "\n---\n";

        SkillMetadata meta = SkillMetadata.parse(content, DIR);

        assertFalse(meta.hasError());
        assertTrue(hasIssue(meta, "DESCRIPTION_TOO_LONG", SkillIssue.Severity.WARN));
    }

    // ==================== 边界情况 ====================

    @Test
    void noFrontmatterIsError() {
        SkillMetadata meta = SkillMetadata.parse("# just a doc, no frontmatter", DIR);

        assertTrue(meta.hasError());
        assertTrue(hasIssue(meta, "MISSING_FRONTMATTER", SkillIssue.Severity.ERROR));
        assertNull(meta.getBody());
    }

    @Test
    void unclosedFrontmatterIsError() {
        SkillMetadata meta = SkillMetadata.parse("---\nname: pdf-processing\n", DIR);

        assertTrue(meta.hasError());
        assertTrue(hasIssue(meta, "MISSING_FRONTMATTER", SkillIssue.Severity.ERROR));
    }

    @Test
    void nullContentIsError() {
        SkillMetadata meta = SkillMetadata.parse(null, DIR);

        assertTrue(meta.hasError());
        assertTrue(hasIssue(meta, "MISSING_SKILL_MD", SkillIssue.Severity.ERROR));
    }

    @Test
    void lenientFallbackOnInvalidYaml() {
        // 描述中带裸冒号，严格 YAML 解析失败 → 宽松逐行解析兜底
        String content = """
                ---
                name: pdf-processing
                description: Use this when: the user asks about PDFs
                ---
                """;

        SkillMetadata meta = SkillMetadata.parse(content, DIR);

        assertFalse(meta.hasError());
        assertEquals("Use this when: the user asks about PDFs", meta.getDescription());
        assertTrue(hasIssue(meta, "FRONTMATTER_LENIENT", SkillIssue.Severity.WARN));
    }

    // ==================== 辅助 ====================

    private static boolean hasIssue(SkillMetadata meta, String code, SkillIssue.Severity severity) {
        return meta.getIssues().stream().anyMatch(i -> i.code().equals(code) && i.severity() == severity);
    }

    @Test
    void computeHashStable() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("skill-test");
        java.nio.file.Files.writeString(dir.resolve("SKILL.md"), "---\nname: test\n---\nbody");
        java.nio.file.Files.writeString(dir.resolve("skill.yaml"), "type: script\n");

        String hash1 = SkillMetadata.computeHash(dir);
        String hash2 = SkillMetadata.computeHash(dir);
        assertEquals(hash1, hash2);
        assertFalse(hash1.isEmpty());

        java.nio.file.Files.writeString(dir.resolve("SKILL.md"), "---\nname: test\n---\nchanged");
        String hash3 = SkillMetadata.computeHash(dir);
        assertFalse(hash1.equals(hash3), "内容变化后 hash 应不同");
    }

    // ==================== 仓库真实技能文件验证 ====================

    @Test
    void realSkillFilesParseCleanly() throws Exception {
        // 与 SkillLoader.resolveSkillsDir 相同的回退逻辑：mvn 在 bootstrap/ 下运行
        java.nio.file.Path skillsDir = java.nio.file.Path.of("skills");
        if (!java.nio.file.Files.isDirectory(skillsDir)) {
            skillsDir = java.nio.file.Path.of(System.getProperty("user.dir")).getParent().resolve("skills");
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isDirectory(skillsDir), "skills 目录不存在，跳过");

        try (var dirs = java.nio.file.Files.list(skillsDir)) {
            List<java.nio.file.Path> skillDirs = dirs.filter(java.nio.file.Files::isDirectory).toList();
            assertFalse(skillDirs.isEmpty(), "仓库应至少包含一个技能");
            for (java.nio.file.Path dir : skillDirs) {
                java.nio.file.Path skillMd = dir.resolve("SKILL.md");
                if (java.nio.file.Files.exists(skillMd)) {
                    String content = java.nio.file.Files.readString(skillMd);
                    SkillMetadata meta = SkillMetadata.parse(content, dir.getFileName().toString());
                    assertFalse(meta.hasError(),
                            () -> dir.getFileName() + " 的 SKILL.md 解析失败: " + meta.getIssues());
                }
                java.nio.file.Path yaml = dir.resolve("skill.yaml");
                if (java.nio.file.Files.exists(yaml)) {
                    Object parsed = new org.yaml.snakeyaml.Yaml().load(
                            java.nio.file.Files.readString(yaml));
                    assertTrue(parsed instanceof Map, dir.getFileName() + " 的 skill.yaml 应为映射");
                }
            }
        }
    }
}
