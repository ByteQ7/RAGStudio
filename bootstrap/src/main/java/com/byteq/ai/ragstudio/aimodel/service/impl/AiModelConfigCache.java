package com.byteq.ai.ragstudio.aimodel.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiModelDO;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiProviderDO;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiModelMapper;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiProviderMapper;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.config.ModelConfigProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * AI 模型配置缓存
 * <p>
 * 实现 {@link ModelConfigProvider} 接口，从数据库加载供应商和模型配置，
 * 在内存中维护一份全量快照，供 {@link com.byteq.ai.ragstudio.infra.model.ModelSelector} 读取。
 * </p>
 * <p>
 * 采用"写后刷新"策略：每次 CRUD 操作后调用 {@link #reloadAndNotify()} 重新加载，
 * 并通过 Redis Topic 通知其他实例同步刷新（多实例部署时配置变更实时生效）。
 * 使用 {@link ReentrantReadWriteLock} 保证读写安全，读操作不加锁（volatile 引用替换）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelConfigCache implements ModelConfigProvider {

    /** 模型配置变更通知 Topic：写操作后发布，其他实例订阅后刷新本地缓存 */
    private static final String CONFIG_CHANGED_TOPIC = "RAGStudio:aimodel:config:changed";

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final ObjectMapper objectMapper;
    private final RedissonClient redisson;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 当前配置快照（volatile 保证多线程可见性） */
    private volatile DynamicModelConfig currentConfig;

    private int listenerId = -1;

    @PostConstruct
    public void init() {
        reload();
        if (currentConfig != null) {
            log.info("AI 模型配置缓存初始化完成: providers={}, models={}",
                    currentConfig.getProviders().size(), currentConfig.getModels().size());
        } else {
            log.warn("AI 模型配置缓存初始化失败（数据库不可用），将在下次 reload 时重试");
        }
        subscribe();
    }

    @PreDestroy
    public void destroy() {
        if (listenerId != -1) {
            try {
                redisson.getTopic(CONFIG_CHANGED_TOPIC).removeListener(listenerId);
            } catch (Exception e) {
                log.debug("取消模型配置变更订阅失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 订阅配置变更通知：其他实例写操作后会发布消息，收到后刷新本地缓存
     */
    private void subscribe() {
        try {
            RTopic topic = redisson.getTopic(CONFIG_CHANGED_TOPIC);
            listenerId = topic.addListener(String.class, (channel, msg) -> {
                log.info("收到模型配置变更通知，刷新本地缓存");
                reload();
            });
            log.info("AI 模型配置变更订阅成功（跨实例同步已启用）");
        } catch (Exception e) {
            log.warn("订阅模型配置变更通知失败，跨实例配置同步不可用: {}", e.getMessage());
        }
    }

    /**
     * 重新从数据库加载全量配置
     */
    public void reload() {
        lock.writeLock().lock();
        try {
            DynamicModelConfig newConfig = loadFromDatabase();
            this.currentConfig = newConfig;
            log.debug("AI 模型配置缓存已刷新: providers={}, models={}",
                    newConfig.getProviders().size(), newConfig.getModels().size());
        } catch (Exception e) {
            log.error("刷新 AI 模型配置缓存失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 写操作后调用：刷新本地缓存，并通过 Redis 通知其他实例同步刷新。
     * <p>
     * 若调用方处于事务中，通知推迟到事务提交后（afterCommit）再执行——
     * 否则远端实例收到通知后读库可能读到未提交数据，刷新出旧配置。
     * Redis 不可用时仅影响跨实例同步，本地刷新不受影响。
     * </p>
     */
    public void reloadAndNotify() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reloadAndNotifyNow();
                }
            });
        } else {
            reloadAndNotifyNow();
        }
    }

    private void reloadAndNotifyNow() {
        reload();
        try {
            redisson.getTopic(CONFIG_CHANGED_TOPIC).publish("changed");
        } catch (Exception e) {
            log.warn("发布模型配置变更通知失败（仅影响跨实例同步）: {}", e.getMessage());
        }
    }

    @Override
    public DynamicModelConfig getConfig() {
        if (currentConfig == null) {
            return DynamicModelConfig.builder().build();
        }
        return currentConfig;
    }

    // 从数据库加载全量配置：1) 加载已启用供应商，构建 providerMap 和 idToName 映射；2) 加载已启用模型，关联供应商名称；3) 组装 DynamicModelConfig 返回
    private DynamicModelConfig loadFromDatabase() {
        // 加载所有已启用的供应商
        List<AiProviderDO> providers = providerMapper.selectList(
                new LambdaQueryWrapper<AiProviderDO>()
                        .eq(AiProviderDO::getEnabled, 1)
        );

        Map<String, DynamicModelConfig.ProviderEntry> providerMap = new HashMap<>();
        // providerId -> providerName 的映射，用于模型关联
        Map<String, String> idToName = new HashMap<>();

        for (AiProviderDO p : providers) {
            Map<String, String> endpoints = parseEndpoints(p.getEndpoints());
            DynamicModelConfig.ProviderEntry entry = DynamicModelConfig.ProviderEntry.builder()
                    .name(p.getName())
                    .url(p.getBaseUrl())
                    .apiKey(p.getApiKey())
                    .endpoints(endpoints)
                    .protocol(StrUtil.isNotBlank(p.getApiProtocol()) ? p.getApiProtocol() : "openai")
                    .build();
            providerMap.put(p.getName(), entry);
            idToName.put(p.getId(), p.getName());
        }

        // 加载所有已启用的模型
        List<AiModelDO> models = modelMapper.selectList(
                new LambdaQueryWrapper<AiModelDO>()
                        .eq(AiModelDO::getEnabled, 1)
                        .orderByAsc(AiModelDO::getPriority)
        );

        List<DynamicModelConfig.ModelEntry> modelEntries = models.stream()
                .filter(m -> idToName.containsKey(m.getProviderId()))
                .map(m -> {
                    String providerName = idToName.get(m.getProviderId());
                    List<Integer> allDims = parseDimensionList(m.getDimension());
                    Integer effectiveDim = resolveEffectiveDimension(m.getDimension());
                    return DynamicModelConfig.ModelEntry.builder()
                            .id(m.getModelId())
                            .provider(providerName)
                            // 供应商请求体使用「供应商侧实际模型名」，modelId 仅是逻辑标识
                            // （如 modelId=qwen-plus → modelName=qwen-plus-latest），
                            // 否则调用方会把逻辑 ID 直接发给供应商导致模型不存在
                            .model(StrUtil.isNotBlank(m.getModelName()) ? m.getModelName() : m.getModelId())
                            .url(StrUtil.isNotBlank(m.getCustomUrl()) ? m.getCustomUrl() : null)
                            .dimension(effectiveDim)
                            .dimensions(allDims)
                            .priority(m.getPriority() != null ? m.getPriority() : 100)
                            .enabled(true)
                            .supportsThinking(m.getSupportsThinking() != null && m.getSupportsThinking() == 1)
                            .supportsMultimodal(m.getSupportsMultimodal() != null && m.getSupportsMultimodal() == 1)
                            .supportsJsonOutput(m.getSupportsJsonOutput() != null && m.getSupportsJsonOutput() == 1)
                            .supportsJsonSchema(m.getSupportsJsonSchema() != null && m.getSupportsJsonSchema() == 1)
                            .isDefault(m.getIsDefault() != null && m.getIsDefault() == 1)
                            .capability(m.getCapability())
                            .protocol(StrUtil.isNotBlank(m.getApiProtocol()) ? m.getApiProtocol() : null)
                            .build();
                })
                .collect(Collectors.toList());

        return DynamicModelConfig.builder()
                .providers(providerMap)
                .models(modelEntries)
                .build();
    }

    // 解析 dimension JSON 字符串为维度列表
    private List<Integer> parseDimensionList(String dimensionJson) {
        if (StrUtil.isBlank(dimensionJson)) return null;
        try {
            if (dimensionJson.trim().startsWith("[")) {
                return objectMapper.readValue(dimensionJson, new TypeReference<List<Integer>>() {});
            }
            int single = Integer.parseInt(dimensionJson.trim());
            return List.of(single);
        } catch (Exception e) {
            log.warn("解析 dimension 失败: {}", dimensionJson, e);
            return null;
        }
    }

    // 解析 dimension JSON 数组，取 ≤2000 的最大值作为有效维度
    // 如 "[1024, 1536, 4096]" 返回 1536；兼容旧数据 "1536" 纯数字字符串
    private Integer resolveEffectiveDimension(String dimensionJson) {
        if (StrUtil.isBlank(dimensionJson)) {
            return null;
        }
        try {
            // 尝试解析为 JSON 数组
            if (dimensionJson.trim().startsWith("[")) {
                List<Integer> dims = objectMapper.readValue(dimensionJson, new TypeReference<List<Integer>>() {});
                return dims.stream()
                        .filter(d -> d <= 2000)
                        .max(Integer::compareTo)
                        .orElse(null);
            }
            // 兼容旧数据：纯数字字符串如 "1536" 或 1536
            int single = Integer.parseInt(dimensionJson.trim());
            return single <= 2000 ? single : null;
        } catch (Exception e) {
            log.warn("解析 dimension 失败: {}, 使用默认值 1536", dimensionJson);
            return 1536;
        }
    }

    // 将供应商 endpoints JSON 字符串解析为 Map，解析失败时返回空 Map
    private Map<String, String> parseEndpoints(String json) {
        if (StrUtil.isBlank(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析供应商 endpoints JSON 失败: {}", json, e);
            return Collections.emptyMap();
        }
    }
}
