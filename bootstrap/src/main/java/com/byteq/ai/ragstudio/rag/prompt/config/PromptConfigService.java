package com.byteq.ai.ragstudio.rag.prompt.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateUtils;
import com.byteq.ai.ragstudio.rag.prompt.controller.request.PromptConfigUpdateRequest;
import com.byteq.ai.ragstudio.rag.prompt.controller.vo.PromptConfigVO;
import com.byteq.ai.ragstudio.rag.prompt.controller.vo.PromptHistoryVO;
import com.byteq.ai.ragstudio.rag.prompt.dao.entity.PromptConfigDO;
import com.byteq.ai.ragstudio.rag.prompt.dao.entity.PromptConfigHistoryDO;
import com.byteq.ai.ragstudio.rag.prompt.dao.mapper.PromptConfigHistoryMapper;
import com.byteq.ai.ragstudio.rag.prompt.dao.mapper.PromptConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 提示词统一配置服务
 * <p>
 * 核心职责：把散落在项目中的提示词集中到 {@code t_prompt_config} 表统一管理，
 * 后管「提示词管理」页编辑后立即热重载（无需重启）。
 * </p>
 * <p>
 * 读取策略（DB 优先、classpath 兜底）：内存维护一份 DB 生效快照（仅 enabled=true），
 * 业务读取命中快照则用之，否则回退 classpath 默认模板（resources/prompt/*.st）。
 * 表未初始化 / 数据库不可用时快照为空，链路自动回退 classpath，不影响现有功能。
 * </p>
 * <p>
 * 热重载：写操作（更新/重置/回滚）后立即 {@link #reload()}；
 * 另提供 {@code @Scheduled} 60s 轮询兜底，防止数据库被直改导致的漏刷新（单实例部署）。
 * </p>
 * <p>
 * 变更历史：每次写操作前将旧内容写入 {@code t_prompt_config_history}（version 对应其内容），
 * 支持查看历史与回滚到指定版本（version=1 即出厂默认）。
 * </p>
 */
@Slf4j
@Service
public class PromptConfigService {

    /** 轮询刷新间隔（毫秒）：单实例下即使绕过后管直改数据库也能在 60s 内生效 */
    private static final long REFRESH_INTERVAL_MS = 60_000;

    private final PromptConfigMapper promptConfigMapper;
    private final PromptConfigHistoryMapper historyMapper;
    private final ResourceLoader resourceLoader;

    /**
     * DB 生效快照：key -> content（仅 enabled=true 的记录）
     */
    private volatile Map<String, String> snapshot = Collections.emptyMap();

    /**
     * classpath 默认模板内容缓存（出厂默认，不随热重载变化）
     */
    private final Map<String, String> classpathCache = new ConcurrentHashMap<>();

    public PromptConfigService(PromptConfigMapper promptConfigMapper,
                               PromptConfigHistoryMapper historyMapper,
                               ResourceLoader resourceLoader) {
        this.promptConfigMapper = promptConfigMapper;
        this.historyMapper = historyMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void init() {
        seedFromClasspath();
        reload();
        log.info("提示词配置初始化完成: 快照 {} 条（DB 优先、classpath 兜底）", snapshot.size());
    }

    /**
     * 轮询兜底刷新：写操作后已即时 reload，此处仅兜底数据库被直改的情况
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
    public void scheduledRefresh() {
        reload();
    }

    // ==================== 读取（供业务链路调用） ====================

    /**
     * 获取 DB 生效的提示词内容；快照未命中（未自定义或禁用）返回 null
     */
    public String getContent(String key) {
        return snapshot.get(key);
    }

    /**
     * 获取生效提示词内容：DB 快照 → classpath 默认模板 → 空串（并告警）
     */
    public String getEffectiveContent(String key) {
        String content = snapshot.get(key);
        if (content != null) {
            return content;
        }
        String def = getClasspathDefault(key);
        if (def != null) {
            return def;
        }
        log.warn("提示词默认内容缺失，返回空串: key={}", key);
        return "";
    }

    /**
     * 获取指定 section 的提示词内容（DB 优先，classpath 兜底）；均未命中返回 null
     */
    public String getSectionContent(String key, String section) {
        String content = snapshot.get(key);
        if (content != null) {
            return PromptTemplateUtils.parseSections(content).get(section);
        }
        String def = getClasspathDefault(key);
        if (def != null) {
            return PromptTemplateUtils.parseSections(def).get(section);
        }
        return null;
    }

    // ==================== 后管读写 ====================

    /**
     * 提示词全量列表（合并注册表与 DB 状态，一次批量查询避免 N+1）
     */
    public List<PromptConfigVO> list() {
        Map<String, PromptConfigDO> rowMap = loadRowMap();
        List<PromptConfigVO> result = new ArrayList<>();
        for (PromptKeys k : PromptKeys.values()) {
            PromptConfigDO row = rowMap.get(k.key());
            String defaultContent = getClasspathDefault(k.key());
            String content = row != null ? row.getContent() : defaultContent;
            boolean customized = row != null && !java.util.Objects.equals(row.getContent(), defaultContent);
            String source = row != null && Boolean.TRUE.equals(row.getEnabled()) && customized ? "db" : "classpath";
            result.add(PromptConfigVO.builder()
                    .key(k.key())
                    .category(k.category())
                    .name(k.displayName())
                    .description(k.description())
                    .content(content)
                    .defaultContent(defaultContent)
                    .variables(k.variables())
                    .version(row != null ? row.getVersion() : 1)
                    .enabled(row != null ? row.getEnabled() : Boolean.TRUE)
                    .source(source)
                    .customized(customized)
                    .updatedBy(row != null ? row.getUpdatedBy() : null)
                    .updateTime(row != null ? row.getUpdateTime() : null)
                    .build());
        }
        return result;
    }

    /**
     * 提示词详情
     */
    public PromptConfigVO detail(String key) {
        PromptKeys meta = requireMeta(key);
        PromptConfigDO row = findByKey(key);
        String defaultContent = getClasspathDefault(key);
        String content = row != null ? row.getContent() : defaultContent;
        boolean customized = row != null && !java.util.Objects.equals(row.getContent(), defaultContent);
        String source = row != null && Boolean.TRUE.equals(row.getEnabled()) && customized ? "db" : "classpath";
        return PromptConfigVO.builder()
                .key(meta.key())
                .category(meta.category())
                .name(row != null ? row.getName() : meta.displayName())
                .description(row != null && row.getDescription() != null ? row.getDescription() : meta.description())
                .content(content)
                .defaultContent(defaultContent)
                .variables(meta.variables())
                .version(row != null ? row.getVersion() : 1)
                .enabled(row != null ? row.getEnabled() : Boolean.TRUE)
                .source(source)
                .customized(customized)
                .updatedBy(row != null ? row.getUpdatedBy() : null)
                .updateTime(row != null ? row.getUpdateTime() : null)
                .build();
    }

    /**
     * 更新提示词：内容写入历史（旧版本）→ 更新主记录（版本 +1）→ 立即热重载
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptConfigDO update(String key, PromptConfigUpdateRequest req) {
        if (req == null || !StringUtils.hasText(req.getContent())) {
            throw new IllegalArgumentException("提示词内容不能为空: " + key);
        }
        PromptConfigDO row = findByKey(key);
        String operator = UserContext.getUsername();
        if (row == null) {
            row = buildSeedRow(requireMeta(key));
            // 与已播种记录保持一致：先将变更前（出厂默认）写入历史，再版本 +1
            recordHistory(row, operator);
            row.setContent(req.getContent().trim());
            row.setVersion(row.getVersion() + 1);
        } else {
            recordHistory(row, operator);
            if (StringUtils.hasText(req.getName())) {
                row.setName(req.getName().trim());
            }
            if (req.getDescription() != null) {
                row.setDescription(req.getDescription().trim());
            }
            row.setContent(req.getContent().trim());
            row.setVersion(row.getVersion() + 1);
        }
        if (req.getEnabled() != null) {
            row.setEnabled(req.getEnabled());
        }
        row.setUpdatedBy(operator);
        row.setUpdateTime(new Date());
        if (row.getId() == null) {
            promptConfigMapper.insert(row);
        } else {
            promptConfigMapper.updateById(row);
        }
        reload();
        log.info("提示词已更新并热重载: key={}, version={}, enabled={}, by={}",
                key, row.getVersion(), row.getEnabled(), operator);
        return row;
    }

    /**
     * 重置为出厂默认（classpath 模板内容），并热重载
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptConfigDO reset(String key) {
        PromptConfigDO row = findByKey(key);
        if (row == null) {
            throw new IllegalArgumentException("提示词不存在，无法重置: " + key);
        }
        String defaultContent = getClasspathDefault(key);
        if (defaultContent == null) {
            throw new IllegalStateException("该提示词无 classpath 默认模板，无法重置: " + key);
        }
        String operator = UserContext.getUsername();
        recordHistory(row, operator);
        row.setContent(defaultContent);
        row.setEnabled(Boolean.TRUE);
        row.setVersion(row.getVersion() + 1);
        row.setUpdatedBy(operator);
        row.setUpdateTime(new Date());
        promptConfigMapper.updateById(row);
        reload();
        log.info("提示词已重置为默认并热重载: key={}, version={}, by={}", key, row.getVersion(), operator);
        return row;
    }

    /**
     * 变更历史（按版本升序）
     */
    public List<PromptHistoryVO> history(String key) {
        requireMeta(key);
        List<PromptConfigHistoryDO> rows = historyMapper.selectList(
                new LambdaQueryWrapper<PromptConfigHistoryDO>()
                        .eq(PromptConfigHistoryDO::getPromptId, key)
                        .orderByAsc(PromptConfigHistoryDO::getVersion));
        List<PromptHistoryVO> result = new ArrayList<>();
        for (PromptConfigHistoryDO row : rows) {
            result.add(PromptHistoryVO.builder()
                    .version(row.getVersion())
                    .content(row.getContent())
                    .updatedBy(row.getUpdatedBy())
                    .updateTime(row.getUpdateTime())
                    .build());
        }
        return result;
    }

    /**
     * 回滚到指定版本：version=1 表示出厂默认；其余取历史记录内容
     */
    @Transactional(rollbackFor = Exception.class)
    public PromptConfigDO rollback(String key, int version) {
        requireMeta(key);
        PromptConfigDO row = findByKey(key);
        if (row == null) {
            throw new IllegalArgumentException("提示词不存在，无法回滚: " + key);
        }
        String target;
        if (version <= 1) {
            // 优先取历史上记录的 v1 内容（与历史弹窗展示一致）；无历史记录时回退 classpath 出厂默认
            PromptConfigHistoryDO h1 = historyMapper.selectOne(
                    new LambdaQueryWrapper<PromptConfigHistoryDO>()
                            .eq(PromptConfigHistoryDO::getPromptId, key)
                            .eq(PromptConfigHistoryDO::getVersion, 1)
                            .last("LIMIT 1"));
            target = h1 != null ? h1.getContent() : getClasspathDefault(key);
        } else {
            PromptConfigHistoryDO h = historyMapper.selectOne(
                    new LambdaQueryWrapper<PromptConfigHistoryDO>()
                            .eq(PromptConfigHistoryDO::getPromptId, key)
                            .eq(PromptConfigHistoryDO::getVersion, version)
                            .last("LIMIT 1"));
            target = h != null ? h.getContent() : null;
        }
        if (target == null) {
            throw new IllegalArgumentException("未找到可回滚的版本内容: key=" + key + ", version=" + version);
        }
        String operator = UserContext.getUsername();
        recordHistory(row, operator);
        row.setContent(target);
        row.setVersion(row.getVersion() + 1);
        row.setUpdatedBy(operator);
        row.setUpdateTime(new Date());
        promptConfigMapper.updateById(row);
        reload();
        log.info("提示词已回滚到版本 v{} 并热重载: key={}, 当前版本={}, by={}",
                version, key, row.getVersion(), operator);
        return row;
    }

    /**
     * 试渲染：用给定 slots 填充提示词内容，便于编辑时校验占位符
     */
    public String preview(String key, Map<String, String> slots) {
        String content = getEffectiveContent(key);
        return PromptTemplateUtils.cleanupPrompt(PromptTemplateUtils.fillSlots(content, slots));
    }

    // ==================== 内部实现 ====================

    /**
     * 启动播种：将注册表中缺失的 key 以 classpath 默认内容写入 DB（幂等，不覆盖已有）
     */
    private void seedFromClasspath() {
        try {
            int seeded = 0;
            for (PromptKeys k : PromptKeys.values()) {
                long exists = promptConfigMapper.selectCount(
                        new LambdaQueryWrapper<PromptConfigDO>().eq(PromptConfigDO::getId, k.key()));
                if (exists > 0) {
                    continue;
                }
                String content = getClasspathDefault(k.key());
                if (content == null) {
                    continue;
                }
                promptConfigMapper.insert(buildSeedRow(k));
                seeded++;
            }
            if (seeded > 0) {
                log.info("提示词配置播种完成: 新增 {} 条", seeded);
            }
        } catch (Exception e) {
            log.warn("提示词配置播种失败（t_prompt_config 表可能未初始化，将回退 classpath 默认值）: {}", e.getMessage());
        }
    }

    private PromptConfigDO buildSeedRow(PromptKeys k) {
        return PromptConfigDO.builder()
                .id(k.key())
                .category(k.category())
                .name(k.displayName())
                .description(k.description())
                .content(getClasspathDefault(k.key()))
                .variables(k.variables())
                .version(1)
                .enabled(Boolean.TRUE)
                .updatedBy("system")
                .updateTime(new Date())
                .build();
    }

    /**
     * 重新加载 DB 生效快照（全量，量级小）
     */
    private void reload() {
        try {
            List<PromptConfigDO> rows = promptConfigMapper.selectList(
                    new LambdaQueryWrapper<PromptConfigDO>()
                            .eq(PromptConfigDO::getEnabled, true));
            Map<String, String> m = new HashMap<>();
            for (PromptConfigDO row : rows) {
                if (row.getEnabled() != null && row.getEnabled()) {
                    m.put(row.getId(), row.getContent());
                }
            }
            this.snapshot = Collections.unmodifiableMap(m);
        } catch (Exception e) {
            log.debug("刷新提示词快照失败（表可能未初始化）: {}", e.getMessage());
            this.snapshot = Collections.emptyMap();
        }
    }

    /**
     * 记录变更历史：将当前记录内容写为「变更前版本」
     */
    private void recordHistory(PromptConfigDO row, String operator) {
        historyMapper.insert(PromptConfigHistoryDO.builder()
                .promptId(row.getId())
                .version(row.getVersion())
                .content(row.getContent())
                .updatedBy(operator)
                .updateTime(new Date())
                .build());
    }

    private PromptConfigDO findByKey(String key) {
        try {
            return promptConfigMapper.selectById(key);
        } catch (Exception e) {
            log.debug("读取提示词配置失败（表可能未初始化）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 批量加载全部提示词行（一次查询，避免列表 N+1）；表未初始化时返回空映射
     */
    private Map<String, PromptConfigDO> loadRowMap() {
        try {
            return promptConfigMapper.selectList(null).stream()
                    .collect(Collectors.toMap(PromptConfigDO::getId, r -> r, (a, b) -> a));
        } catch (Exception e) {
            log.debug("读取提示词配置列表失败（表可能未初始化）: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private PromptKeys requireMeta(String key) {
        return PromptKeys.fromKey(key)
                .orElseThrow(() -> new IllegalArgumentException("未知的提示词 key: " + key));
    }

    /**
     * 读取 classpath 默认模板内容（带缓存；无模板则返回 null）
     */
    private String getClasspathDefault(String key) {
        return PromptKeys.fromKey(key)
                .map(k -> readClasspath(k.classpathPath()))
                .orElse(null);
    }

    private String readClasspath(String path) {
        if (path == null) {
            return null;
        }
        return classpathCache.computeIfAbsent(path, p -> {
            String location = p.startsWith("classpath:") ? p : "classpath:" + p;
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.warn("classpath 提示词模板不存在: {}", p);
                return null;
            }
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("读取 classpath 提示词模板失败: {}", p, e);
                return null;
            }
        });
    }
}
