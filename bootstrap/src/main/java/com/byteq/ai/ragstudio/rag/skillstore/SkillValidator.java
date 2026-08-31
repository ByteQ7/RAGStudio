package com.byteq.ai.ragstudio.rag.skillstore;

import com.byteq.ai.ragstudio.rag.core.skill.SkillIssue;
import com.byteq.ai.ragstudio.rag.core.skill.SkillMetadata;
import com.byteq.ai.ragstudio.rag.core.skill.SecurityAuditor;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SKILL 入库校验器（内存文件集校验，供管理面使用）
 * <p>
 * 与运行时 {@link SkillLoader} 的目录校验共用 {@link SkillMetadata} 解析规则，
 * 并在入库关卡上更严格：name 不合规、可执行配置缺失等在保存时即拒绝，
 * 而不是等到运行时才失败。ERROR 拒绝入库，WARN 放行（展示给管理员）。
 */
@Slf4j
public final class SkillValidator {

    private SkillValidator() {}

    /** scripts/ 下参与静态审计的文本文件大小上限（超出跳过审计，避免大文本误报/耗时） */
    private static final int AUDIT_MAX_BYTES = 512 * 1024;

    /** 按扩展名判定的文本文件白名单（不在名单内的扩展名做 NUL 字节嗅探兜底） */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "yaml", "yml", "json", "txt", "py", "sh", "js", "ts", "mjs", "cjs",
            "rb", "php", "xml", "html", "htm", "css", "csv", "tsv", "sql", "toml", "ini", "cfg",
            "conf", "properties", "bat", "ps1", "dockerfile", "gitignore", "license", "log", "env");

    /**
     * 校验结果。{@code textFlags} 为每个文件的文本/二进制分类（存储与 diff 依据）；
     * manifest 为解析后的元数据快照（JSON 序列化后落库）。
     */
    public record ValidatedSkill(
            String name,
            String description,
            String skillType,
            String declaredVersion,
            Map<String, Object> manifest,
            Map<String, Boolean> textFlags,
            List<SkillIssue> issues) {

        public boolean hasError() {
            return issues.stream().anyMatch(i -> i.severity() == SkillIssue.Severity.ERROR);
        }

        public List<SkillIssue> errors() {
            return bySeverity(issues, SkillIssue.Severity.ERROR);
        }

        public List<SkillIssue> warnings() {
            return bySeverity(issues, SkillIssue.Severity.WARN);
        }
    }

    /**
     * 校验最终文件集（路径已规范化）。
     *
     * @param expectedName 期望的技能名（更新/收编场景非空，包内 frontmatter name 必须一致）；
     *                     新建场景传 null，以包内 frontmatter name 为准
     * @param files        规范化后的文件集（相对路径 → 内容），顺序无关
     */
    public static ValidatedSkill validate(String expectedName, Map<String, byte[]> files) {
        List<SkillIssue> issues = new ArrayList<>();
        Map<String, Boolean> textFlags = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            textFlags.put(e.getKey(), isText(e.getKey(), e.getValue()));
        }

        byte[] skillMdBytes = files.get("SKILL.md");
        if (skillMdBytes == null) {
            issues.add(SkillIssue.error("NO_SKILL_MD", "缺少 SKILL.md（技能元数据唯一来源，必须位于包根目录）"));
            return new ValidatedSkill(expectedName, null, null, null, Map.of(), textFlags, issues);
        }

        SkillMetadata meta = SkillMetadata.parse(new String(skillMdBytes, StandardCharsets.UTF_8), expectedName);
        issues.addAll(meta.getIssues());

        String name = meta.getName();
        if (name == null || name.isBlank()) {
            issues.add(SkillIssue.error("NAME_MISSING", "frontmatter 缺少必填字段 name"));
        } else {
            // 运行时目录校验对 name 规范仅 WARN；入库为 ERROR——name 是 DB 主键、目录名与工具标识
            if (!name.matches(SkillMetadata.NAME_PATTERN)) {
                issues.add(SkillIssue.error("NAME_INVALID",
                        "name 不符合命名规范（仅小写字母/数字/连字符，不以连字符开头/结尾）：" + name));
            }
            if (expectedName != null && !name.equals(expectedName)) {
                issues.add(SkillIssue.error("NAME_MISMATCH",
                        "SKILL.md frontmatter name(" + name + ") 与目标技能名(" + expectedName + ")不一致"));
            }
        }
        if (meta.getDescription() == null || meta.getDescription().isBlank()) {
            issues.add(SkillIssue.error("DESCRIPTION_MISSING", "frontmatter 缺少必填字段 description"));
        }

        // skill.yaml 执行配置校验（规则对齐 SkillLoader.loadExecutionConfig，缺失项提升为 ERROR）
        String type = null;
        Map<String, Object> config = null;
        Map<String, Object> parameters = null;
        byte[] yamlBytes = files.get("skill.yaml");
        if (yamlBytes != null) {
            String yamlContent = new String(yamlBytes, StandardCharsets.UTF_8);
            Object parsed;
            try {
                parsed = new Yaml().load(yamlContent);
            } catch (Exception e) {
                issues.add(SkillIssue.error("YAML_PARSE_FAILED", "skill.yaml 解析失败: " + e.getMessage()));
                parsed = null;
            }
            if (parsed != null) {
                if (!(parsed instanceof Map)) {
                    issues.add(SkillIssue.error("YAML_TYPE",
                            "skill.yaml 格式错误：期望键值映射，实际为 " + parsed.getClass().getSimpleName()));
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> raw = (Map<String, Object>) parsed;
                    type = raw.get("type") == null ? null : String.valueOf(raw.get("type")).trim().toLowerCase(Locale.ROOT);
                    if (type != null && !List.of("http", "script", "command").contains(type)) {
                        issues.add(SkillIssue.error("TYPE_INVALID", "skill.yaml type 必须为 http/script/command，当前为 " + type));
                        type = null;
                    }
                    if (raw.get("config") instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cfg = (Map<String, Object>) m;
                        config = cfg;
                        if ("script".equals(type)) {
                            Object scriptFile = cfg.get("scriptFile");
                            if (scriptFile instanceof String s && !s.isBlank()
                                    && !files.containsKey("scripts/" + s)) {
                                issues.add(SkillIssue.error("SCRIPT_FILE_MISSING",
                                        "config.scriptFile(" + s + ") 在文件集中不存在（应位于 scripts/ 下）"));
                            }
                        }
                        if ("command".equals(type) && cmdBlank(cfg)) {
                            issues.add(SkillIssue.error("COMMAND_MISSING", "command 类型的 SKILL 缺少 config.command"));
                        }
                    } else if (type != null) {
                        issues.add(SkillIssue.error("CONFIG_MISSING", type + " 类型的 SKILL 缺少 config 配置"));
                    }
                    if (raw.get("parameters") instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> params = (Map<String, Object>) m;
                        parameters = params;
                    } else if (raw.get("parameters") != null) {
                        issues.add(SkillIssue.error("PARAMETERS_TYPE", "skill.yaml parameters 应为键值映射"));
                    }
                }
            }
        }

        // scripts/ 下文本脚本静态审计（WARN：命中高危模式提示管理员，运行期另有沙箱 + 命令审计防线）
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String path = e.getKey();
            if (!path.startsWith("scripts/") || Boolean.FALSE.equals(textFlags.get(path))) {
                continue;
            }
            byte[] content = e.getValue();
            if (content.length > AUDIT_MAX_BYTES) {
                continue;
            }
            SecurityAuditor.AuditResult audit = SecurityAuditor.audit(new String(content, StandardCharsets.UTF_8));
            if (!audit.allowed()) {
                issues.add(SkillIssue.warn("SCRIPT_SECURITY_HINT",
                        path + " 命中高危模式（" + audit.reason() + "），请确认脚本用途"));
            }
        }

        String declaredVersion = meta.getVersion();
        Map<String, Object> manifest = buildManifest(meta, name, type, config, parameters, files);
        return new ValidatedSkill(name, meta.getDescription(), type, declaredVersion, manifest, textFlags, issues);
    }

    private static boolean cmdBlank(Map<String, Object> cfg) {
        if (cfg == null) {
            return true;
        }
        Object command = cfg.get("command");
        return !(command instanceof String s) || s.isBlank();
    }

    private static Map<String, Object> buildManifest(SkillMetadata meta, String name, String type,
                                                     Map<String, Object> config, Map<String, Object> parameters,
                                                     Map<String, byte[]> files) {
        LinkedHashMap<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", name);
        manifest.put("description", meta.getDescription());
        manifest.put("license", meta.getLicense());
        manifest.put("compatibility", meta.getCompatibility());
        manifest.put("metadata", meta.getMetadata());
        manifest.put("type", type);
        manifest.put("config", config);
        manifest.put("parameters", parameters);
        manifest.put("scriptFiles", topLevels(files, "scripts/"));
        manifest.put("referenceFiles", topLevels(files, "references/"));
        return manifest;
    }

    /** 目录一级文件名列表（与 SkillLoader.scanDirList 语义一致） */
    private static List<String> topLevels(Map<String, byte[]> files, String prefix) {
        return files.keySet().stream()
                .filter(p -> p.startsWith(prefix) && !p.substring(prefix.length()).contains("/"))
                .map(p -> p.substring(prefix.length()))
                .sorted()
                .collect(Collectors.toList());
    }

    /** 文本/二进制分类：扩展名白名单优先，其余做首 8KB NUL 字节嗅探 */
    static boolean isText(String path, byte[] bytes) {
        String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (TEXT_EXTENSIONS.contains(ext)) {
            return true;
        }
        int scanLen = Math.min(bytes.length, 8192);
        for (int i = 0; i < scanLen; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private static List<SkillIssue> bySeverity(List<SkillIssue> issues, SkillIssue.Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity)
                .collect(Collectors.toUnmodifiableList());
    }
}
