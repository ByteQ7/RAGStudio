package com.byteq.ai.ragstudio.core.parser.mineru;

import com.byteq.ai.ragstudio.core.parser.ParseEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MinerU 配置服务
 * <p>
 * 读取 {@code t_mineru_config} 表配置并合并静态默认值（{@link MineruProperties}），
 * 提供本地/远程端点的解析、决策，以及端点健康状态缓存。
 * </p>
 * <p>
 * 关键方法：
 * <ul>
 *   <li>{@link #resolveEndpoint(ParseEngine)} — 按解析引擎决策出实际要调用的端点</li>
 *   <li>{@link #hasUsableEndpoint()} — 是否有可用的 MinerU 端点（用于 AUTO 决策）</li>
 *   <li>{@link #loadVO()} — 返回给前端展示/编辑的配置视图</li>
 *   <li>{@link #saveVO(MineruConfigVO)} — 保存前端编辑后的配置</li>
 *   <li>{@link #probe()} — 连通性探测（健康检查）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class MineruConfigService {

    /**
     * 单行配置的固定主键
     */
    private static final String CONFIG_ID = "single";

    /**
     * 配置加载缓存（60s 过期，避免每次解析都查库）
     */
    private static final long CACHE_TTL_MS = 60_000;

    private final MineruConfigMapper mineruConfigMapper;
    private final MineruProperties mineruProperties;
    private final MineruClient mineruClient;

    /**
     * 配置缓存：value[0] 为加载时间戳
     */
    private volatile Map.Entry<Long, MineruConfigDO> cached = null;

    public MineruConfigService(MineruConfigMapper mineruConfigMapper,
                               MineruProperties mineruProperties,
                               MineruClient mineruClient) {
        this.mineruConfigMapper = mineruConfigMapper;
        this.mineruProperties = mineruProperties;
        this.mineruClient = mineruClient;
    }

    // ==================== 端点决策 ====================

    /**
     * 按解析引擎决策出实际要调用的端点
     * <p>
     * 本地/远程端点：要求对应开关开启且 baseUrl 已配置。
     * 多模态/AUTO：AUTO 时优先可用端点，否则返回 null（由解析器回退多模态）。
     * </p>
     *
     * @param engine 解析引擎偏好（不允许为 null）
     * @return 目标端点；不可用时返回 null
     */
    public MineruEndpoint resolveEndpoint(ParseEngine engine) {
        if (engine == null) {
            return null;
        }
        MineruConfigDO cfg = loadConfig();

        switch (engine) {
            case LOCAL_MINERU -> {
                if (isEnabled(cfg.getLocalEnabled())) {
                    String url = firstNonBlank(cfg.getLocalBaseUrl(), mineruProperties.getLocal().getBaseUrl());
                    if (StringUtils.hasText(url)) {
                        return new MineruEndpoint(url, firstNonBlank(cfg.getLocalBackend(), mineruProperties.getLocal().getBackend(), "pipeline"),
                                firstNonBlank(cfg.getLocalLang(), mineruProperties.getLocal().getLang(), "ch"), null);
                    }
                }
                return null;
            }
            case REMOTE_MINERU -> {
                if (isEnabled(cfg.getRemoteEnabled())) {
                    String url = firstNonBlank(cfg.getRemoteBaseUrl(), mineruProperties.getRemote().getBaseUrl());
                    if (StringUtils.hasText(url)) {
                        return new MineruEndpoint(url, firstNonBlank(cfg.getRemoteBackend(), mineruProperties.getRemote().getBackend(), "pipeline"),
                                firstNonBlank(cfg.getRemoteLang(), mineruProperties.getRemote().getLang(), "ch"),
                                firstNonBlank(cfg.getRemoteApiKey(), mineruProperties.getRemote().getApiKey(), null));
                    }
                }
                return null;
            }
            case AUTO -> {
                // AUTO：优先本地端点，其次远程端点
                MineruEndpoint local = resolveEndpoint(ParseEngine.LOCAL_MINERU);
                if (local != null) {
                    return local;
                }
                return resolveEndpoint(ParseEngine.REMOTE_MINERU);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * 是否有可用的 MinerU 端点（AUTO 决策用）
     */
    public boolean hasUsableEndpoint() {
        return mineruProperties.isEnabled()
                && (resolveEndpoint(ParseEngine.LOCAL_MINERU) != null
                    || resolveEndpoint(ParseEngine.REMOTE_MINERU) != null);
    }

    // ==================== 配置读写 ====================

    /**
     * 加载当前配置（带缓存）
     */
    private MineruConfigDO loadConfig() {
        Map.Entry<Long, MineruConfigDO> c = cached;
        if (c != null && System.currentTimeMillis() - c.getKey() < CACHE_TTL_MS) {
            return c.getValue();
        }
        MineruConfigDO cfg = mineruConfigMapper.selectById(CONFIG_ID);
        if (cfg == null) {
            cfg = MineruConfigDO.builder().id(CONFIG_ID).build();
        }
        cached = Map.entry(System.currentTimeMillis(), cfg);
        return cfg;
    }

    /**
     * 使配置缓存失效（保存后调用）
     */
    private void invalidateCache() {
        cached = null;
    }

    /**
     * 加载配置视图（供前端展示）
     */
    public MineruConfigVO loadVO() {
        MineruConfigDO cfg = loadConfig();
        return MineruConfigVO.builder()
                .local(MineruConfigVO.EndpointVO.builder()
                        .enabled(isEnabled(cfg.getLocalEnabled()))
                        .baseUrl(firstNonBlank(cfg.getLocalBaseUrl(), mineruProperties.getLocal().getBaseUrl()))
                        .backend(firstNonBlank(cfg.getLocalBackend(), mineruProperties.getLocal().getBackend(), "pipeline"))
                        .lang(firstNonBlank(cfg.getLocalLang(), mineruProperties.getLocal().getLang(), "ch"))
                        .build())
                .remote(MineruConfigVO.EndpointVO.builder()
                        .enabled(isEnabled(cfg.getRemoteEnabled()))
                        .baseUrl(firstNonBlank(cfg.getRemoteBaseUrl(), mineruProperties.getRemote().getBaseUrl()))
                        .backend(firstNonBlank(cfg.getRemoteBackend(), mineruProperties.getRemote().getBackend(), "pipeline"))
                        .lang(firstNonBlank(cfg.getRemoteLang(), mineruProperties.getRemote().getLang(), "ch"))
                        .apiKey(firstNonBlank(cfg.getRemoteApiKey(), mineruProperties.getRemote().getApiKey(), null))
                        .build())
                .timeoutSeconds(mineruProperties.getTimeoutSeconds())
                .minTextLength(mineruProperties.getMinTextLength())
                .build();
    }

    /**
     * 保存配置（来自前端编辑）
     */
    public void saveVO(MineruConfigVO vo) {
        MineruConfigDO cfg = loadConfig();
        if (vo.getLocal() != null) {
            cfg.setLocalEnabled(vo.getLocal().getEnabled());
            cfg.setLocalBaseUrl(trimToNull(vo.getLocal().getBaseUrl()));
            cfg.setLocalBackend(trimToNull(vo.getLocal().getBackend()));
            cfg.setLocalLang(trimToNull(vo.getLocal().getLang()));
        }
        if (vo.getRemote() != null) {
            cfg.setRemoteEnabled(vo.getRemote().getEnabled());
            cfg.setRemoteBaseUrl(trimToNull(vo.getRemote().getBaseUrl()));
            cfg.setRemoteApiKey(trimToNull(vo.getRemote().getApiKey()));
            cfg.setRemoteBackend(trimToNull(vo.getRemote().getBackend()));
            cfg.setRemoteLang(trimToNull(vo.getRemote().getLang()));
        }
        if (cfg.getId() == null) {
            cfg.setId(CONFIG_ID);
            mineruConfigMapper.insert(cfg);
        } else {
            mineruConfigMapper.updateById(cfg);
        }
        invalidateCache();
        log.info("MinerU 配置已更新: local={}, remote={}",
                cfg.getLocalEnabled(), cfg.getRemoteEnabled());
    }

    /**
     * 连通性探测：返回带 reachable 标记的视图
     */
    public MineruConfigVO probe() {
        MineruConfigVO vo = loadVO();
        MineruEndpoint local = resolveEndpoint(ParseEngine.LOCAL_MINERU);
        MineruEndpoint remote = resolveEndpoint(ParseEngine.REMOTE_MINERU);
        if (local != null && vo.getLocal() != null) {
            vo.getLocal().setReachable(mineruClient.ping(local));
        }
        if (remote != null && vo.getRemote() != null) {
            vo.getRemote().setReachable(mineruClient.ping(remote));
        }
        return vo;
    }

    // ==================== 工具方法 ====================

    private static boolean isEnabled(Boolean v) {
        return v != null && v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    private static String trimToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }
}