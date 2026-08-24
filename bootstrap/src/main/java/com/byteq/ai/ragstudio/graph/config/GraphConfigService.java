package com.byteq.ai.ragstudio.graph.config;

import com.byteq.ai.ragstudio.aimodel.controller.vo.DefaultModelConfigVO;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.Map;

/**
 * Graph RAG 运行期配置服务
 * <p>
 * 总开关仅由 {@code t_graph_config} 表控制（后管「知识图谱」页维护），
 * 不读取 yaml/环境变量；表未初始化（V3 SQL 未执行）时视为关闭，不影响既有检索链路。
 * 抽取 LLM 复用 {@code t_default_model_config} 的 {@code graph_extract} 场景键（缺省回退对话默认模型）。
 * </p>
 */
@Slf4j
@Service
public class GraphConfigService {

    /**
     * 单行配置的固定主键
     */
    private static final String CONFIG_ID = "single";

    /**
     * 抽取模型场景键（与 GraphProperties.extract.model-key 保持一致）
     */
    private static final String EXTRACT_SCENE_KEY = "graph_extract";

    /**
     * 对话默认模型场景键
     */
    private static final String CHAT_SCENE_KEY = "chat";

    /**
     * 配置加载缓存（60s 过期，避免每次检索/入库都查库）
     */
    private static final long CACHE_TTL_MS = 60_000;

    private final GraphConfigMapper graphConfigMapper;
    private final GraphProperties properties;
    private final DefaultModelConfigService defaultModelConfigService;

    /**
     * 配置缓存：value[0] 为加载时间戳
     */
    private volatile Map.Entry<Long, GraphConfigDO> cached = null;

    public GraphConfigService(GraphConfigMapper graphConfigMapper,
                              GraphProperties properties,
                              DefaultModelConfigService defaultModelConfigService) {
        this.graphConfigMapper = graphConfigMapper;
        this.properties = properties;
        this.defaultModelConfigService = defaultModelConfigService;
    }

    // ==================== 生效值决策 ====================

    /**
     * 图谱总开关是否开启（仅读取 DB 配置；无行/表未初始化时为 false）
     */
    public boolean isEnabled() {
        GraphConfigDO cfg = loadConfig();
        return cfg.getEnabled() != null && cfg.getEnabled();
    }

    /**
     * 图谱检索通道开关是否开启（DB 配置优先，缺失回退 yaml 静态默认）
     */
    public boolean isRetrievalEnabled() {
        GraphConfigDO cfg = loadConfig();
        if (cfg.getRetrievalEnabled() != null) {
            return cfg.getRetrievalEnabled();
        }
        return properties.getRetrieval().isEnabled();
    }

    // ==================== 配置读写 ====================

    /**
     * 加载当前配置（带缓存；无行或表未初始化时返回空配置，不落库）
     */
    private GraphConfigDO loadConfig() {
        Map.Entry<Long, GraphConfigDO> c = cached;
        if (c != null && System.currentTimeMillis() - c.getKey() < CACHE_TTL_MS) {
            return c.getValue();
        }
        GraphConfigDO cfg;
        try {
            cfg = graphConfigMapper.selectById(CONFIG_ID);
        } catch (Exception e) {
            log.debug("读取图谱运行期配置失败（可能未执行 V3 SQL），视为关闭: {}", e.getMessage());
            cfg = null;
        }
        if (cfg == null) {
            cfg = GraphConfigDO.builder().id(CONFIG_ID).build();
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
     * 加载配置视图（供前端展示，含抽取模型与回退目标信息）
     */
    public GraphConfigVO loadVO() {
        DefaultModelConfigVO extract = defaultModelConfigService.getConfig(EXTRACT_SCENE_KEY);
        DefaultModelConfigVO chat = defaultModelConfigService.getConfig(CHAT_SCENE_KEY);
        String extractModelId = extract != null ? extract.getModelId() : null;
        return GraphConfigVO.builder()
                .enabled(isEnabled())
                .retrievalEnabled(isRetrievalEnabled())
                .extractModelId(extractModelId)
                .extractModelName(extract != null ? extract.getModelName() : null)
                .followsChatDefault(extractModelId == null)
                .chatModelId(chat != null ? chat.getModelId() : null)
                .chatModelName(chat != null ? chat.getModelName() : null)
                .build();
    }

    /**
     * 保存配置（来自前端编辑）：总开关/检索开关写 t_graph_config；
     * 抽取模型写 graph_extract 场景（空串表示跟随对话默认模型，null 表示未变更不处理）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveVO(GraphConfigVO vo) {
        GraphConfigDO cfg = loadConfig();
        // 立即失效缓存：避免事务回滚后缓存残留被修改的对象（loadConfig 返回的即缓存实例）
        invalidateCache();
        if (vo.getEnabled() != null) {
            cfg.setEnabled(vo.getEnabled());
        }
        if (vo.getRetrievalEnabled() != null) {
            cfg.setRetrievalEnabled(vo.getRetrievalEnabled());
        }
        cfg.setUpdateTime(new Date());
        if (cfg.getId() == null) {
            cfg.setId(CONFIG_ID);
            graphConfigMapper.insert(cfg);
        } else {
            graphConfigMapper.updateById(cfg);
        }

        String modelLog = "未变更";
        if (vo.getExtractModelId() != null) {
            if (StringUtils.hasText(vo.getExtractModelId())) {
                defaultModelConfigService.updateConfig(EXTRACT_SCENE_KEY, vo.getExtractModelId().trim());
                modelLog = vo.getExtractModelId().trim();
            } else {
                defaultModelConfigService.deleteByKey(EXTRACT_SCENE_KEY);
                modelLog = "跟随默认模型";
            }
        }
        log.info("Graph RAG 配置已更新: enabled={}, retrieval={}, extractModel={}",
                cfg.getEnabled(), cfg.getRetrievalEnabled(), modelLog);
    }
}