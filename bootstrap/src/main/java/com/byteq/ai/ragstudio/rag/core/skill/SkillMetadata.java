package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md frontmatter 解析与校验（兼容 Agent Skills 开放标准）
 * <p>
 * SKILL.md 是技能的单一元数据来源，文件头为 YAML frontmatter：
 * <pre>
 * ---
 * name: pdf-processing
 * description: 提取 PDF 文本。当用户提到 PDF 时使用。
 * license: Apache-2.0
 * compatibility: Requires Python 3.14+
 * metadata:
 *   author: example-org
 *   version: "1.2.0"
 * ---
 * </pre>
 * <p>
 * 解析策略：
 * <ul>
 *   <li>严格解析失败时回退到宽松的逐行解析（兼容带冒号的未加引号描述等非法 YAML）</li>
 *   <li>校验不通过不抛异常，而是记录 {@link SkillIssue}，由调用方决定宽容加载或跳过</li>
 * </ul>
 */
public final class SkillMetadata {

    /** name 命名约束：小写字母/数字/连字符，1-64 字符，不得以连字符开头/结尾，不得出现连续连字符 */
    public static final String NAME_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*$";
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    public static final int MAX_COMPATIBILITY_LENGTH = 500;

    private String name;
    private String description;
    private String license;
    private String compatibility;
    private Map<String, String> metadata = Map.of();

    /** frontmatter 之后的 Markdown 正文（剥离后的指令部分） */
    private String body;

    /** 解析与校验过程中收集的诊断信息 */
    private final List<SkillIssue> issues = new ArrayList<>();

    /**
     * 解析 SKILL.md 全文，并执行校验。
     *
     * @param content SKILL.md 文件内容
     * @param dirName 技能所在目录名（用于 name==目录名 校验）
     * @return 解析结果（即使有 ERROR 也会返回，调用方通过 {@link #hasError()} 判断是否跳过）
     */
    public static SkillMetadata parse(String content, String dirName) {
        SkillMetadata meta = new SkillMetadata();
        if (content == null) {
            meta.issues.add(SkillIssue.error("MISSING_SKILL_MD", "SKILL.md 文件不存在或为空"));
            return meta;
        }

        Frontmatter fm = extractFrontmatter(content);
        if (fm == null) {
            meta.issues.add(SkillIssue.error("MISSING_FRONTMATTER",
                    "SKILL.md 缺少 YAML frontmatter（文件必须以 --- 开头的元数据块起始）"));
            return meta;
        }
        meta.body = fm.body();

        Map<String, Object> raw = parseYaml(fm.frontmatter(), meta);
        if (raw == null) {
            return meta;
        }

        meta.name = str(raw.get("name"));
        meta.description = str(raw.get("description"));
        meta.license = str(raw.get("license"));
        meta.compatibility = str(raw.get("compatibility"));
        meta.metadata = extractMetadata(raw.get("metadata"), meta);

        meta.validate(dirName);
        return meta;
    }

    // ==================== 校验 ====================

    private void validate(String dirName) {
        if (StrUtil.isBlank(name)) {
            issues.add(SkillIssue.error("NAME_MISSING", "frontmatter 缺少必填字段 name"));
        } else {
            if (name.length() > MAX_NAME_LENGTH) {
                issues.add(SkillIssue.warn("NAME_TOO_LONG",
                        "name 长度 " + name.length() + " 超过上限 " + MAX_NAME_LENGTH + " 字符"));
            }
            if (!name.matches(NAME_PATTERN)) {
                issues.add(SkillIssue.warn("NAME_INVALID",
                        "name 不符合命名规范（仅小写字母/数字/连字符）：" + name));
            }
            if (StrUtil.isNotBlank(dirName) && !name.equals(dirName)) {
                issues.add(SkillIssue.warn("NAME_DIR_MISMATCH",
                        "frontmatter name(" + name + ") 与目录名(" + dirName + ")不一致，标准要求二者相同"));
            }
        }

        if (StrUtil.isBlank(description)) {
            issues.add(SkillIssue.error("DESCRIPTION_MISSING", "frontmatter 缺少必填字段 description"));
        } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
            issues.add(SkillIssue.warn("DESCRIPTION_TOO_LONG",
                    "description 长度 " + description.length() + " 超过建议上限 " + MAX_DESCRIPTION_LENGTH + " 字符"));
        }

        if (StrUtil.isNotBlank(compatibility) && compatibility.length() > MAX_COMPATIBILITY_LENGTH) {
            issues.add(SkillIssue.warn("COMPATIBILITY_TOO_LONG",
                    "compatibility 长度超过建议上限 " + MAX_COMPATIBILITY_LENGTH + " 字符"));
        }
    }

    // ==================== 内部工具 ====================

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> extractMetadata(Object v, SkillMetadata meta) {
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    result.put(String.valueOf(e.getKey()),
                            e.getValue() == null ? "" : String.valueOf(e.getValue()));
                }
            }
            return result;
        }
        meta.issues.add(SkillIssue.warn("METADATA_TYPE", "metadata 应为键值对映射，当前为 " + v.getClass().getSimpleName()));
        return Map.of();
    }

    /** frontmatter 提取：文件必须以 --- 开头，取第二个 --- 之前的内容为元数据块 */
    private static Frontmatter extractFrontmatter(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---")) {
            return null;
        }
        int secondSep = normalized.indexOf("\n---", 3);
        if (secondSep < 0) {
            return null;
        }
        String frontmatter = normalized.substring(3, secondSep);
        String body = normalized.substring(secondSep + 4);
        return new Frontmatter(frontmatter, body.stripLeading().stripTrailing());
    }

    /**
     * 解析 YAML 元数据块。
     * 严格解析失败（非法 YAML，如描述中裸冒号）时回退到宽松逐行解析，
     * 并记录 WARN 提示技能作者修复。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String frontmatter, SkillMetadata meta) {
        try {
            Object parsed = new Yaml().load(frontmatter);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            meta.issues.add(SkillIssue.error("FRONTMATTER_TYPE",
                    "frontmatter 应为键值映射，实际为 " + parsed.getClass().getSimpleName()));
            return null;
        } catch (Exception strictErr) {
            // 宽松逐行解析：key: value（只按第一个冒号切分，value 原样保留）
            Map<String, Object> lenient = new LinkedHashMap<>();
            for (String line : frontmatter.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("---")) {
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                lenient.put(key, value);
            }
            if (lenient.isEmpty()) {
                meta.issues.add(SkillIssue.error("FRONTMATTER_UNPARSEABLE",
                        "frontmatter 无法解析: " + strictErr.getMessage()));
                return null;
            }
            meta.issues.add(SkillIssue.warn("FRONTMATTER_LENIENT",
                    "frontmatter 存在非法 YAML，已按宽松模式解析（建议修复）: " + strictErr.getMessage()));
            return lenient;
        }
    }

    /**
     * 计算技能内容指纹（SHA-256），用于版本检测与安装校验。
     * 仅对 SKILL.md 与 skill.yaml 计算，避免对 scripts/ 大文件（如行政区划数据）反复哈希。
     */
    public static String computeHash(Path skillDir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            hashFile(md, skillDir.resolve("SKILL.md"));
            hashFile(md, skillDir.resolve("skill.yaml"));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        }
    }

    private static void hashFile(MessageDigest md, Path file) throws IOException {
        if (Files.exists(file) && Files.isRegularFile(file)) {
            md.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            md.update(Files.readAllBytes(file));
        }
    }

    private record Frontmatter(String frontmatter, String body) {}

    // ==================== 访问器 ====================

    public String getName() { return name; }

    public String getDescription() { return description; }

    public String getLicense() { return license; }

    public String getCompatibility() { return compatibility; }

    public Map<String, String> getMetadata() { return metadata; }

    public String getBody() { return body; }

    public List<SkillIssue> getIssues() { return List.copyOf(issues); }

    /** 是否因 ERROR 级别问题不可用（应跳过加载） */
    public boolean hasError() {
        return issues.stream().anyMatch(i -> i.severity() == SkillIssue.Severity.ERROR);
    }

    /** 从 metadata 中读取版本号（标准约定 metadata.version），无则返回 null */
    public String getVersion() {
        String v = metadata.get("version");
        return StrUtil.isBlank(v) ? null : v;
    }
}
