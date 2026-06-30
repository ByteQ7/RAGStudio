package com.byteq.ai.ragstudio.rag.core.skill;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * SKILL 加载器
 * <p>
 * 负责扫描 skills/ 目录结构，加载 skill.yaml / SKILL.md / scripts/ / references/
 * 到 Redis 缓存，并维护内存中的摘要列表供 StreamChatPipeline 使用。
 * <p>
 * 目录结构约定：
 * <pre>
 * skills/
 * ├── weather/
 * │   ├── skill.yaml       # 必填 — 定义
 * │   ├── SKILL.md         # 可选 — 说明书
 * │   ├── scripts/         # 可选 — 可执行脚本
 * │   └── references/      # 可选 — 参考资料
 * └── ip-info/
 *     └── skill.yaml
 * </pre>
 * <p>
 * 热更新采用定时轮询（默认 15 秒），对比目录的 mtime 变化。
 */
@Slf4j
@Service
public class SkillLoader {

    private static final String REDIS_KEY_PREFIX = "RAGStudio:skill:";
    private static final String REDIS_LIST_KEY = "RAGStudio:skill:list";

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final Path skillsDir;
    private final long pollIntervalMs;

    /** 内存缓存：SKILL 名称 → SkillDefinition，供 Agent 构建时使用 */
    private final ConcurrentHashMap<String, SkillDefinition> skillCache = new ConcurrentHashMap<>();

    /** 上次扫描的目录 mtime 缓存 */
    private final ConcurrentHashMap<String, Long> lastModifiedCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private long lastPollTime = 0;

    public SkillLoader(
            RedissonClient redisson,
            ObjectMapper objectMapper,
            @Value("${rag.skills.dir:skills}") String skillsDirPath,
            @Value("${rag.skills.poll-interval-ms:15000}") long pollIntervalMs) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.skillsDir = resolveSkillsDir(skillsDirPath);
        this.pollIntervalMs = pollIntervalMs;
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
        startPolling();
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    // ==================== 外部接口 ====================

    /**
     * 扫描 skills/ 目录并重新加载所有 SKILL 到 Redis
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

        // 记录当前已加载的 SKILL 名，用于检测哪些被删除了
        ConcurrentHashMap<String, Boolean> loaded = new ConcurrentHashMap<>();
        List<Map<String, String>> summaryList = new ArrayList<>();

        for (Path dir : skillDirs) {
            String skillName = dir.getFileName().toString();
            Path yamlFile = dir.resolve("skill.yaml");

            if (!Files.exists(yamlFile)) {
                log.warn("SKILL 目录缺少 skill.yaml，跳过: {}", dir);
                continue;
            }

            try {
                SkillDefinition def = loadSkill(dir, skillName);
                skillCache.put(skillName, def);
                loaded.put(skillName, Boolean.TRUE);

                // 写入 Redis
                String json = objectMapper.writeValueAsString(def);
                RBucket<String> bucket = redisson.getBucket(REDIS_KEY_PREFIX + skillName);
                bucket.set(json);

                // 汇总列表
                summaryList.add(Map.of(
                        "name", def.getName(),
                        "description", def.getDescription() != null ? def.getDescription() : ""
                ));

                log.info("SKILL 已加载: {} (type={}, dir={})", skillName, def.getType(), dir);
            } catch (Exception e) {
                log.error("加载 SKILL 失败: {}", dir, e);
            }
        }

        // 清理已不存在的 SKILL
        List<String> removed = new ArrayList<>();
        for (String name : skillCache.keySet()) {
            if (!loaded.containsKey(name)) {
                removed.add(name);
                skillCache.remove(name);
                redisson.getBucket(REDIS_KEY_PREFIX + name).delete();
                log.info("SKILL 已卸载（目录不存在）: {}", name);
            }
        }

        // 写入 SKILL 列表到 Redis
        try {
            String listJson = objectMapper.writeValueAsString(summaryList);
            redisson.getBucket(REDIS_LIST_KEY).set(listJson);
        } catch (Exception e) {
            log.warn("写入 SKILL 列表到 Redis 失败", e);
        }

        lastPollTime = System.currentTimeMillis();
        log.info("SKILL 加载完成: {} 个技能（新增 {}，移除 {}）",
                loaded.size(), loaded.size() - removed.size(), removed.size());
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
     * 获取 SKILL 摘要列表（用于前端展示）
     */
    public List<Map<String, String>> listSkillSummaries() {
        List<Map<String, String>> result = new ArrayList<>();
        for (SkillDefinition def : skillCache.values()) {
            result.add(Map.of(
                    "name", def.getName(),
                    "description", def.getDescription() != null ? def.getDescription() : ""
            ));
        }
        return result;
    }

    /**
     * 读取 SKILL 下 scripts/ 中的脚本文件内容
     */
    public byte[] getScriptContent(String skillName, String fileName) {
        SkillDefinition def = skillCache.get(skillName);
        if (def == null || def.getSkillDir() == null) {
            return null;
        }
        Path scriptPath = def.getSkillDir().resolve("scripts").resolve(fileName);
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
        Path refPath = def.getSkillDir().resolve("references").resolve(fileName);
        return readFileBytes(refPath);
    }

    // ==================== 内部方法 ====================

    private SkillDefinition loadSkill(Path dir, String skillName) throws IOException {
        // 1. 解析 skill.yaml
        Path yamlFile = dir.resolve("skill.yaml");
        String yamlContent = Files.readString(yamlFile, StandardCharsets.UTF_8);

        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        Object parsed = yaml.load(yamlContent);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("skill.yaml 格式错误：期望一个 Map，实际为 " + parsed.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) parsed;

        SkillDefinition def = objectMapper.convertValue(raw, SkillDefinition.class);
        def.setSkillDir(dir);

        // 校验必填字段
        if (StrUtil.isBlank(def.getName())) {
            throw new IllegalArgumentException("skill.yaml 缺少 name 字段");
        }
        if (StrUtil.isBlank(def.getType())) {
            throw new IllegalArgumentException("skill.yaml 缺少 type 字段");
        }
        if (StrUtil.isBlank(def.getDescription())) {
            throw new IllegalArgumentException("skill.yaml 缺少 description 字段");
        }

        // 2. 读取 SKILL.md（可选）
        Path docFile = dir.resolve("SKILL.md");
        if (Files.exists(docFile) && Files.isRegularFile(docFile)) {
            def.setSkillDoc(Files.readString(docFile, StandardCharsets.UTF_8));
        }

        // 3. 扫描 scripts/ 目录（可选）
        Path scriptsDir = dir.resolve("scripts");
        if (Files.exists(scriptsDir) && Files.isDirectory(scriptsDir)) {
            try (Stream<Path> files = Files.list(scriptsDir)) {
                def.setScriptFiles(files
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .toList());
            }
        } else {
            def.setScriptFiles(List.of());
        }

        // 4. 扫描 references/ 目录（可选）
        Path refsDir = dir.resolve("references");
        if (Files.exists(refsDir) && Files.isDirectory(refsDir)) {
            try (Stream<Path> files = Files.list(refsDir)) {
                def.setReferenceFiles(files
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .toList());
            }
        } else {
            def.setReferenceFiles(List.of());
        }

        return def;
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
        redisson.getBucket(REDIS_LIST_KEY).delete();
    }

    // ==================== 热更新轮询 ====================

    private void startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skill-loader");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollForChanges,
                pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("SKILL 热更新轮询已启动，间隔: {}ms", pollIntervalMs);
    }

    private void pollForChanges() {
        if (!Files.exists(skillsDir)) {
            return;
        }
        try {
            boolean changed = detectChanges(skillsDir);
            if (changed) {
                log.info("检测到 SKILL 目录变更，重新加载...");
                scanAndLoad();
            }
        } catch (Exception e) {
            log.warn("SKILL 轮询检测异常", e);
        }
    }

    /**
     * 递归检测 skills/ 目录树的 mtime 是否有变化
     */
    private boolean detectChanges(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).anyMatch(file -> {
                try {
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    String key = file.toString();
                    Long prev = lastModifiedCache.get(key);
                    if (prev == null || prev != mtime) {
                        lastModifiedCache.put(key, mtime);
                        return true;
                    }
                    return false;
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException e) {
            return false;
        }
    }
}
