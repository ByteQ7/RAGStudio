package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * SKILL 加载器
 * <p>
 * 负责扫描 skills/ 目录结构，加载 SKILL.md（元数据唯一来源）+ 可选的 skill.yaml（执行配置）
 * 到内存缓存，并写入轻量 catalog 到 Redis 供外部消费。
 * <p>
 * 目录结构约定（兼容 Agent Skills 标准）：
 * <pre>
 * skills/
 * ├── weather/                # 标准模式：SKILL.md 必填，skill.yaml 可选
 * │   ├── SKILL.md            # 必填 — frontmatter(name/description) + 指令正文
 * │   ├── skill.yaml          # 可选 — type/config/parameters 执行配置
 * │   ├── scripts/            # 可选 — 可执行脚本
 * │   └── references/         # 可选 — 参考资料
 * └── legacy-skill/
 *     └── skill.yaml          # 旧格式兼容：仅 skill.yaml（加载但告警建议迁移）
 * </pre>
 */
@Slf4j
@Service
public class SkillLoader {

    private static final String REDIS_KEY_PREFIX = "RAGStudio:skill:";
    private static final String REDIS_LIST_KEY = "RAGStudio:skill:list";

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final Path skillsDir;

    /** 内存缓存：技能名 → SkillDefinition，供 Agent 构建时使用 */
    private final ConcurrentHashMap<String, SkillDefinition> skillCache = new ConcurrentHashMap<>();

    /** 加载失败技能的诊断：目录名 → 错误信息（供管理界面展示） */
    private final ConcurrentHashMap<String, List<SkillIssue>> failedSkills = new ConcurrentHashMap<>();

    public SkillLoader(
            RedissonClient redisson,
            ObjectMapper objectMapper,
            @Value("${rag.skills.dir:skills}") String skillsDirPath) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.skillsDir = resolveSkillsDir(skillsDirPath);
    }

    /**
     * 解析 SKILL 目录路径
     * <p>
     * 优先使用配置的路径；如果不存在，尝试从当前工作目录的父目录查找
     * （适配 mvn spring-boot:run 在 bootstrap/ 下运行的情况）。
     */
    private static Path resolveSkillsDir(String configured) {
        Path path = Path.of(configured);
        if (Files.exists(path) && Files.isDirectory(path)) {
            return path.toAbsolutePath().normalize();
        }
        // 尝试从 user.dir 的父目录查找（适配 bootstrap/ 子模块运行）
        Path parentPath = Path.of(System.getProperty("user.dir")).getParent().resolve(configured);
        if (Files.exists(parentPath) && Files.isDirectory(parentPath)) {
            return parentPath.normalize();
        }
        // 回退到配置路径
        return path;
    }

    @PostConstruct
    public void init() {
        scanAndLoad();
        // 仅在启动时加载一次，不启动热更新轮询
    }

    // ==================== 外部接口 ====================

    /**
     * 扫描 skills/ 目录并重新加载所有 SKILL
     */
    public synchronized void scanAndLoad() {
        if (!Files.exists(skillsDir)) {
            try {
                Files.createDirectories(skillsDir);
                log.info("SKILL 目录不存在，已自动创建: {}", skillsDir.toAbsolutePath());
            } catch (IOException e) {
                log.warn("无法创建 SKILL 目录: {}", skillsDir.toAbsolutePath(), e);
            }
            return;
        }

        if (!Files.isDirectory(skillsDir)) {
            log.warn("SKILL 路径不是目录: {}", skillsDir.toAbsolutePath());
            return;
        }

        // 扫描子目录
        List<Path> skillDirs;
        try (var dirs = Files.list(skillsDir)) {
            skillDirs = dirs.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.error("扫描 SKILL 目录失败", e);
            return;
        }

        if (skillDirs.isEmpty()) {
            log.info("SKILL 目录为空，未加载任何技能");
            clearCache();
            return;
        }

        // 记录当前已加载/失败的技能，用于检测哪些被删除了
        ConcurrentHashMap<String, Boolean> loaded = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Boolean> failed = new ConcurrentHashMap<>();
        List<Map<String, String>> summaryList = new ArrayList<>();

        for (Path dir : skillDirs) {
            String dirName = dir.getFileName().toString();
            try {
                SkillDefinition def = loadSkill(dir, dirName);
                SkillDefinition existing = skillCache.put(def.getName(), def);
                if (existing != null) {
                    log.warn("SKILL 名称冲突: [{}] 同时存在于 {} 与 {}，后者生效（建议保持目录名与 frontmatter name 唯一）",
                            def.getName(), existing.getSkillDir(), dir);
                }
                loaded.put(def.getName(), Boolean.TRUE);

                writeCatalog(def);
                summaryList.add(buildSummary(def));

                log.info("SKILL 已加载: {} (type={}, dir={})", def.getName(),
                        def.getType() != null ? def.getType() : "doc", dir);
            } catch (SkillLoadException e) {
                // 解析/校验 ERROR：记录诊断，不加载
                failed.put(dirName, Boolean.TRUE);
                failedSkills.put(dirName, e.getIssues());
                summaryList.add(buildFailureSummary(dirName, e.getIssues()));
                log.warn("SKILL 加载失败（已跳过）: {}: {}", dirName, e.getMessage());
            } catch (Exception e) {
                failed.put(dirName, Boolean.TRUE);
                failedSkills.put(dirName, List.of(
                        SkillIssue.error("LOAD_FAILED", "加载异常: " + e.getMessage())));
                summaryList.add(buildFailureSummary(dirName, List.of(
                        SkillIssue.error("LOAD_FAILED", "加载异常: " + e.getMessage()))));
                log.error("加载 SKILL 失败: {}", dir, e);
            }
        }

        // 清理已不存在的 SKILL（含失败诊断）
        removeStale(loaded, failed);

        // 写入 SKILL 列表（含失败项）到 Redis
        try {
            String listJson = objectMapper.writeValueAsString(summaryList);
            redisson.getBucket(REDIS_LIST_KEY).set(listJson);
        } catch (Exception e) {
            log.warn("写入 SKILL 列表到 Redis 失败", e);
        }

        log.info("SKILL 加载完成: 成功 {} 个，失败 {} 个", loaded.size(), failed.size());
    }

    /**
     * 获取所有已加载的 SKILL 定义（内存缓存）
     */
    public List<SkillDefinition> getAllSkills() {
        return List.copyOf(skillCache.values());
    }

    /**
     * 获取单个 SKILL 定义
     */
    public SkillDefinition getSkill(String name) {
        return skillCache.get(name);
    }

    /**
     * 获取 SKILL 摘要列表（用于前端展示，含加载失败的技能及错误原因）
     */
    public List<Map<String, String>> listSkillSummaries() {
        List<Map<String, String>> result = new ArrayList<>();
        for (SkillDefinition def : skillCache.values()) {
            result.add(buildSummary(def));
        }
        for (Map.Entry<String, List<SkillIssue>> e : failedSkills.entrySet()) {
            result.add(buildFailureSummary(e.getKey(), e.getValue()));
        }
        result.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
        return result;
    }

    /**
     * 读取 SKILL 下 scripts/ 中的脚本文件内容
     * <p>
     * fileName 由 LLM 生成（可能被用户问题诱导），必须校验路径不越界到技能目录之外。
     * </p>
     */
    public byte[] getScriptContent(String skillName, String fileName) {
        SkillDefinition def = skillCache.get(skillName);
        if (def == null || def.getSkillDir() == null) {
            return null;
        }
        Path scriptPath = resolveInside(def.getSkillDir().resolve("scripts"), fileName);
        if (scriptPath == null) {
            return null;
        }
        return readFileBytes(scriptPath);
    }

    /**
     * 读取 SKILL 下 references/ 中的参考文件内容
     */
    public byte[] getReferenceContent(String skillName, String fileName) {
        SkillDefinition def = skillCache.get(skillName);
        if (def == null || def.getSkillDir() == null) {
            return null;
        }
        Path refPath = resolveInside(def.getSkillDir().resolve("references"), fileName);
        if (refPath == null) {
            return null;
        }
        return readFileBytes(refPath);
    }

    // 将 fileName 限定在 baseDir 目录内：拒绝绝对路径与 .. 越界，防止任意文件读取
    private static Path resolveInside(Path baseDir, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Path normalized = baseDir.resolve(fileName).normalize();
        if (!normalized.startsWith(baseDir.normalize())) {
            log.warn("SKILL 文件路径越界被拒绝: baseDir={}, fileName={}", baseDir, fileName);
            return null;
        }
        return normalized;
    }

    // ==================== 内部方法 ====================

    /**
     * 加载单个技能：SKILL.md 元数据（必填）+ skill.yaml 执行配置（可选）。
     * 旧格式（仅 skill.yaml）兼容加载，但记录迁移告警。
     *
     * @throws SkillLoadException 存在 ERROR 级别问题，技能不可用
     */
    private SkillDefinition loadSkill(Path dir, String dirName) throws IOException, SkillLoadException {
        Path skillMd = dir.resolve("SKILL.md");
        Path yamlFile = dir.resolve("skill.yaml");

        if (!Files.exists(skillMd) && !Files.exists(yamlFile)) {
            throw new SkillLoadException(List.of(
                    SkillIssue.error("NO_MANIFEST", "目录中既无 SKILL.md 也无 skill.yaml，已跳过")));
        }

        SkillDefinition def = new SkillDefinition();
        def.setSkillDir(dir);

        // 1. SKILL.md → 元数据（标准模式）
        if (Files.exists(skillMd) && Files.isRegularFile(skillMd)) {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            SkillMetadata meta = SkillMetadata.parse(content, dirName);
            List<SkillIssue> issues = new ArrayList<>(meta.getIssues());
            if (meta.hasError()) {
                throw new SkillLoadException(issues);
            }
            def.setName(meta.getName());
            def.setDescription(meta.getDescription());
            def.setLicense(meta.getLicense());
            def.setCompatibility(meta.getCompatibility());
            def.setMetadata(meta.getMetadata());
            def.setVersion(meta.getVersion());
            def.setSkillDoc(meta.getBody());
            def.setIssues(issues);
        }

        // 2. skill.yaml → 执行配置（可选）
        if (Files.exists(yamlFile) && Files.isRegularFile(yamlFile)) {
            loadExecutionConfig(yamlFile, def, dirName, Files.exists(skillMd));
        }

        // 3. 校验必填字段（旧格式路径：从 skill.yaml 读 name/description）
        if (StrUtil.isBlank(def.getName()) || StrUtil.isBlank(def.getDescription())) {
            throw new SkillLoadException(List.of(
                    SkillIssue.error("NAME_DESCRIPTION_MISSING",
                            "未获取到 name/description：标准模式须在 SKILL.md frontmatter 中声明，"
                                    + "旧格式须在 skill.yaml 中声明")));
        }

        // 4. 计算内容指纹
        def.setContentHash(SkillMetadata.computeHash(dir));

        // 5. 扫描 scripts/ 与 references/
        def.setScriptFiles(scanDirList(dir.resolve("scripts")));
        def.setReferenceFiles(scanDirList(dir.resolve("references")));

        // 6. 汇总诊断
        if (def.getIssues() == null || def.getIssues().isEmpty()) {
            def.setIssues(List.of());
        }
        return def;
    }

    /** 解析 skill.yaml 执行配置；若 yaml 中声明了 name/description 且 SKILL.md 缺失，按旧格式兼容 */
    private void loadExecutionConfig(Path yamlFile, SkillDefinition def, String dirName, boolean hasSkillMd)
            throws SkillLoadException {
        String yamlContent;
        try {
            yamlContent = Files.readString(yamlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SkillLoadException(List.of(
                    SkillIssue.error("YAML_READ_FAILED", "读取 skill.yaml 失败: " + e.getMessage())));
        }

        Object parsed;
        try {
            parsed = new org.yaml.snakeyaml.Yaml().load(yamlContent);
        } catch (Exception e) {
            throw new SkillLoadException(List.of(
                    SkillIssue.error("YAML_PARSE_FAILED", "skill.yaml 解析失败: " + e.getMessage())));
        }
        if (!(parsed instanceof Map)) {
            throw new SkillLoadException(List.of(
                    SkillIssue.error("YAML_TYPE", "skill.yaml 格式错误：期望键值映射，实际为 "
                            + parsed.getClass().getSimpleName())));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) parsed;

        // 旧格式兼容：仅 skill.yaml（无 SKILL.md）时从 yaml 读取元数据
        if (!hasSkillMd) {
            def.setName(str(raw.get("name")));
            def.setDescription(str(raw.get("description")));
            def.setIssues(List.of(SkillIssue.warn("LEGACY_FORMAT",
                    "旧格式技能（仅 skill.yaml）。建议迁移为 SKILL.md frontmatter（name/description 标准来源）")));
        }

        String type = str(raw.get("type"));
        def.setType(type != null ? type.trim().toLowerCase() : null);

        Object config = raw.get("config");
        if (config instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) config;
            def.setConfig(cfg);

            // 校验 script 类型的 scriptFile 存在性
            if ("script".equals(def.getType())) {
                Object scriptFile = cfg.get("scriptFile");
                if (scriptFile instanceof String s && !s.isBlank()
                        && !Files.exists(def.getSkillDir().resolve("scripts").resolve(s))) {
                    appendIssue(def, SkillIssue.warn("SCRIPT_FILE_MISSING",
                            "config.scriptFile(" + s + ") 在 scripts/ 下不存在"));
                }
            }
        }

        Object parameters = raw.get("parameters");
        if (parameters instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) parameters;
            def.setParameters(params);
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private static void appendIssue(SkillDefinition def, SkillIssue issue) {
        List<SkillIssue> issues = new ArrayList<>(def.getIssues() != null ? def.getIssues() : List.of());
        issues.add(issue);
        def.setIssues(issues);
    }

    private List<String> scanDirList(Path dir) throws IOException {
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList();
            }
        }
        return List.of();
    }

    /** 清理已删除的技能目录（内存缓存 + Redis + 失败诊断） */
    private void removeStale(Map<String, Boolean> loaded, Map<String, Boolean> failed) {
        List<String> removed = new ArrayList<>();
        for (String name : skillCache.keySet()) {
            if (!loaded.containsKey(name)) {
                removed.add(name);
                skillCache.remove(name);
                redisson.getBucket(REDIS_KEY_PREFIX + name).delete();
                log.info("SKILL 已卸载（目录不存在）: {}", name);
            }
        }
        for (String dirName : failedSkills.keySet()) {
            if (!failed.containsKey(dirName)) {
                failedSkills.remove(dirName);
            }
        }
        if (!removed.isEmpty()) {
            log.info("SKILL 卸载 {} 个: {}", removed.size(), removed);
        }
    }

    /** 写入轻量 catalog 到 Redis（不包含 skillDoc 等大字段，避免多节点反序列化失效） */
    private void writeCatalog(SkillDefinition def) {
        try {
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("name", def.getName());
            catalog.put("description", def.getDescription());
            catalog.put("version", def.getVersion());
            catalog.put("type", def.getType());
            catalog.put("license", def.getLicense());
            catalog.put("compatibility", def.getCompatibility());
            catalog.put("contentHash", def.getContentHash());
            catalog.put("scriptCount", def.getScriptFiles() != null ? def.getScriptFiles().size() : 0);
            catalog.put("referenceCount", def.getReferenceFiles() != null ? def.getReferenceFiles().size() : 0);
            redisson.getBucket(REDIS_KEY_PREFIX + def.getName()).set(objectMapper.writeValueAsString(catalog));
        } catch (Exception e) {
            log.warn("写入 SKILL catalog 到 Redis 失败: {}", def.getName(), e);
        }
    }

    private Map<String, String> buildSummary(SkillDefinition def) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", def.getName());
        m.put("description", def.getDescription() != null ? def.getDescription() : "");
        m.put("version", def.getVersion() != null ? def.getVersion() : "");
        m.put("type", def.getType() != null ? def.getType() : "doc");
        m.put("errors", formatIssues(def.getIssues(), SkillIssue.Severity.ERROR));
        m.put("warnings", formatIssues(def.getIssues(), SkillIssue.Severity.WARN));
        m.put("loaded", "true");
        return m;
    }

    private Map<String, String> buildFailureSummary(String dirName, List<SkillIssue> issues) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", dirName);
        m.put("description", "");
        m.put("version", "");
        m.put("type", "");
        m.put("errors", formatIssues(issues, SkillIssue.Severity.ERROR));
        m.put("warnings", formatIssues(issues, SkillIssue.Severity.WARN));
        m.put("loaded", "false");
        return m;
    }

    private static String formatIssues(List<SkillIssue> issues, SkillIssue.Severity severity) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        return String.join(" | ", issues.stream()
                .filter(i -> i.severity() == severity)
                .map(i -> i.code() + ": " + i.message())
                .toList());
    }

    private byte[] readFileBytes(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            log.warn("读取 SKILL 文件失败: {}", path, e);
            return null;
        }
    }

    private void clearCache() {
        skillCache.clear();
        failedSkills.clear();
        redisson.getBucket(REDIS_LIST_KEY).delete();
    }

    // ==================== 异常 ====================

    /** 技能加载失败（存在 ERROR 级别诊断），携带全部诊断信息 */
    private static class SkillLoadException extends Exception {
        private final List<SkillIssue> issues;

        SkillLoadException(List<SkillIssue> issues) {
            super(issues.stream().map(SkillIssue::message).reduce((a, b) -> a + "; " + b).orElse("unknown"));
            this.issues = issues;
        }

        List<SkillIssue> getIssues() {
            return Collections.unmodifiableList(issues);
        }
    }
}
