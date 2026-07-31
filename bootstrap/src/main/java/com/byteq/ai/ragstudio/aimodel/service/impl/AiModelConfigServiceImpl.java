package com.byteq.ai.ragstudio.aimodel.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.ModelPriorityItem;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiModelVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiProviderVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.ConnectivityResultVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.FetchModelsResultVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.RemoteModelInfoVO;
import com.byteq.ai.ragstudio.aimodel.adapter.ProviderAdapter;
import com.byteq.ai.ragstudio.aimodel.adapter.ProviderAdapterRegistry;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiModelDO;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiProviderDO;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiModelMapper;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiProviderMapper;
import com.byteq.ai.ragstudio.aimodel.service.AiModelConfigService;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.http.HttpModelFactory;
import com.byteq.ai.ragstudio.infra.http.ModelHttpClient;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 模型配置管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelConfigServiceImpl implements AiModelConfigService {

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiModelConfigCache configCache;
    private final HttpModelFactory chatModelFactory;
    private final ModelHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ProviderAdapterRegistry adapterRegistry;
    private final FileStorageService fileStorageService;
    private final ProtocolRegistry protocolRegistry;

    // ==================== 供应商管理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createProvider(AiProviderCreateRequest request) {
        if (StrUtil.isBlank(request.getName())) {
            throw new ClientException("供应商标识不能为空");
        }
        if (StrUtil.isBlank(request.getBaseUrl())) {
            throw new ClientException("API 基础地址不能为空");
        }

        Map<String, String> mergedEndpoints = fillDefaultEndpoints(
                request.getName(), request.getEndpoints());

        AiProviderDO provider = AiProviderDO.builder()
                .name(request.getName().trim())
                .displayName(request.getDisplayName())
                .baseUrl(request.getBaseUrl().trim())
                .apiKey(request.getApiKey())
                .apiProtocol(StrUtil.isNotBlank(request.getApiProtocol()) ? request.getApiProtocol() : "openai")
                .endpoints(serializeEndpoints(mergedEndpoints))
                .enabled(request.getEnabled() != null ? request.getEnabled() : 1)
                .build();

        try {
            Long count = providerMapper.selectCount(
                    new LambdaQueryWrapper<AiProviderDO>().eq(AiProviderDO::getName, request.getName())
            );
            if (count > 0) {
                throw new ServiceException("供应商标识已存在：" + request.getName());
            }

            providerMapper.insert(provider);
        } catch (DuplicateKeyException e) {
            throw new ClientException("供应商标识已存在：" + request.getName());
        }
        log.info("创建 AI 供应商: id={}, name={}", provider.getId(), provider.getName());

        configCache.reload();
        return provider.getId();
    }

    @Override
    public void updateProvider(String id, AiProviderUpdateRequest request) {
        AiProviderDO existing = providerMapper.selectById(id);
        if (existing == null) {
            throw new ClientException("供应商不存在：" + id);
        }

        if (StrUtil.isNotBlank(request.getDisplayName())) {
            existing.setDisplayName(request.getDisplayName());
        }
        if (StrUtil.isNotBlank(request.getBaseUrl())) {
            existing.setBaseUrl(request.getBaseUrl().trim());
        }
        if (request.getApiKey() != null) {
            existing.setApiKey(request.getApiKey());
        }
        if (request.getEndpoints() != null) {
            Map<String, String> mergedEndpoints = fillDefaultEndpoints(
                    existing.getName(), request.getEndpoints());
            existing.setEndpoints(serializeEndpoints(mergedEndpoints));
        }
        if (request.getEnabled() != null) {
            if (request.getEnabled() == 0) {
                // 自动禁用该供应商下的所有模型
                modelMapper.update(null,
                        new LambdaUpdateWrapper<AiModelDO>()
                                .eq(AiModelDO::getProviderId, id)
                                .eq(AiModelDO::getEnabled, 1)
                                .set(AiModelDO::getEnabled, 0)
                );
                log.info("禁用供应商 {} 时自动禁用其下所有模型", id);
            }
            existing.setEnabled(request.getEnabled());
        }
        if (StrUtil.isNotBlank(request.getApiProtocol())) {
            existing.setApiProtocol(request.getApiProtocol());
        }

        providerMapper.updateById(existing);
        log.info("更新 AI 供应商: id={}, name={}", id, existing.getName());

        // 清除该供应商下所有模型的 Spring AI 实例缓存
        evictModelsByProvider(id);
        configCache.reload();
    }

    @Override
    @Transactional
    public void deleteProvider(String id) {
        AiProviderDO existing = providerMapper.selectById(id);
        if (existing == null) {
            throw new ClientException("供应商不存在：" + id);
        }

        // 检查是否有关联的已启用模型
        Long modelCount = modelMapper.selectCount(
                new LambdaQueryWrapper<AiModelDO>()
                        .eq(AiModelDO::getProviderId, id)
                        .eq(AiModelDO::getEnabled, 1)
        );
        if (modelCount > 0) {
            throw new ServiceException("该供应商下有 " + modelCount + " 个已启用的模型，请先禁用或删除后再删除供应商");
        }

        providerMapper.deleteById(id);
        log.info("删除 AI 供应商: id={}, name={}", id, existing.getName());

        // 清除该供应商下所有模型的 Spring AI 实例缓存
        evictModelsByProvider(id);
        configCache.reload();
    }

    @Override
    public AiProviderVO getProvider(String id) {
        AiProviderDO provider = providerMapper.selectById(id);
        if (provider == null) {
            throw new ClientException("供应商不存在：" + id);
        }
        return toProviderVO(provider);
    }

    @Override
    public List<AiProviderVO> listProviders() {
        List<AiProviderDO> providers = providerMapper.selectList(
                new LambdaQueryWrapper<AiProviderDO>().orderByAsc(AiProviderDO::getName)
        );
        return providers.stream().map(this::toProviderVO).collect(Collectors.toList());
    }

    // ==================== 模型管理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String createModel(AiModelCreateRequest request) {
        if (StrUtil.isBlank(request.getProviderId())) {
            throw new ClientException("供应商 ID 不能为空");
        }
        if (StrUtil.isBlank(request.getModelId())) {
            throw new ClientException("模型标识不能为空");
        }
        if (StrUtil.isBlank(request.getModelName())) {
            throw new ClientException("模型名称不能为空");
        }
        if (StrUtil.isBlank(request.getCapability())) {
            throw new ClientException("能力类型不能为空");
        }

        // 校验供应商存在
        AiProviderDO provider = providerMapper.selectById(request.getProviderId());
        if (provider == null) {
            throw new ClientException("供应商不存在：" + request.getProviderId());
        }

        AiModelDO model = AiModelDO.builder()
                .providerId(request.getProviderId())
                .modelId(request.getModelId().trim())
                .modelName(request.getModelName().trim())
                .capability(request.getCapability().toUpperCase())
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : 0)
                .priority(request.getPriority() != null ? request.getPriority() : 100)
                .enabled(request.getEnabled() != null ? request.getEnabled() : 1)
                .supportsThinking(request.getSupportsThinking() != null ? request.getSupportsThinking() : 0)
                .supportsMultimodal(request.getSupportsMultimodal() != null ? request.getSupportsMultimodal() : 0)
                .dimension(serializeDimension(request.getDimension()))
                .customUrl(request.getCustomUrl())
                .apiProtocol(request.getApiProtocol())
                .build();

        try {
            // 校验 modelId 唯一
            Long count = modelMapper.selectCount(
                    new LambdaQueryWrapper<AiModelDO>().eq(AiModelDO::getModelId, request.getModelId())
            );
            if (count > 0) {
                throw new ServiceException("模型标识已存在：" + request.getModelId());
            }

            // 如果设置为默认，先取消同 capability 下的其他默认
            if (model.getIsDefault() == 1) {
                clearDefaultForCapability(model.getCapability(), null);
            }

            modelMapper.insert(model);
        } catch (DuplicateKeyException e) {
            throw new ClientException("模型标识已存在：" + request.getModelId());
        }
        log.info("创建 AI 模型: id={}, modelId={}, capability={}", model.getId(), model.getModelId(), model.getCapability());

        configCache.reload();
        return model.getId();
    }

    @Override
    public void updateModel(String id, AiModelUpdateRequest request) {
        AiModelDO existing = modelMapper.selectById(id);
        if (existing == null) {
            throw new ClientException("模型不存在：" + id);
        }

        LambdaUpdateWrapper<AiModelDO> wrapper = new LambdaUpdateWrapper<AiModelDO>()
                .eq(AiModelDO::getId, id);

        if (StrUtil.isNotBlank(request.getProviderId()) && !request.getProviderId().equals(existing.getProviderId())) {
            AiProviderDO provider = providerMapper.selectById(request.getProviderId());
            if (provider == null) {
                throw new ClientException("供应商不存在：" + request.getProviderId());
            }
            wrapper.set(AiModelDO::getProviderId, request.getProviderId());
        }
        if (StrUtil.isNotBlank(request.getModelName())) {
            wrapper.set(AiModelDO::getModelName, request.getModelName().trim());
        }
        if (StrUtil.isNotBlank(request.getCapability())) {
            wrapper.set(AiModelDO::getCapability, request.getCapability().toUpperCase());
        }
        if (request.getPriority() != null) {
            wrapper.set(AiModelDO::getPriority, request.getPriority());
        }
        if (request.getEnabled() != null) {
            wrapper.set(AiModelDO::getEnabled, request.getEnabled());
        }
        if (request.getSupportsThinking() != null) {
            wrapper.set(AiModelDO::getSupportsThinking, request.getSupportsThinking());
        }
        if (request.getSupportsMultimodal() != null) {
            wrapper.set(AiModelDO::getSupportsMultimodal, request.getSupportsMultimodal());
        }
        if (request.getDimension() != null) {
            wrapper.set(AiModelDO::getDimension, serializeDimension(request.getDimension()));
        }
        if (request.getCustomUrl() != null) {
            wrapper.set(AiModelDO::getCustomUrl, request.getCustomUrl().isBlank() ? null : request.getCustomUrl().trim());
        }
        if (request.getApiProtocol() != null) {
            wrapper.set(AiModelDO::getApiProtocol, request.getApiProtocol().isBlank() ? null : request.getApiProtocol().trim());
        }

        modelMapper.update(null, wrapper);
        log.info("更新 AI 模型: id={}, modelId={}", id, existing.getModelId());

        chatModelFactory.evict(existing.getModelId());
        configCache.reload();
    }

    @Override
    public void deleteModel(String id) {
        AiModelDO existing = modelMapper.selectById(id);
        if (existing == null) {
            throw new ClientException("模型不存在：" + id);
        }

        // 先物理删除之前软删除的同 model_id 记录，避免 uk_ai_model_model_id 冲突
        modelMapper.forceDeleteByModelId(existing.getModelId());

        modelMapper.deleteById(id);
        log.info("删除 AI 模型: id={}, modelId={}", id, existing.getModelId());

        chatModelFactory.evict(existing.getModelId());
        configCache.reload();
    }

    @Override
    public AiModelVO getModel(String id) {
        AiModelDO model = modelMapper.selectById(id);
        if (model == null) {
            throw new ClientException("模型不存在：" + id);
        }
        return toModelVO(model);
    }

    @Override
    public List<AiModelVO> listModels(String capability) {
        // 只查询已启用且有 API Key 的供应商
        List<AiProviderDO> activeProviders = providerMapper.selectList(
                new LambdaQueryWrapper<AiProviderDO>()
                        .eq(AiProviderDO::getEnabled, 1)
                        .isNotNull(AiProviderDO::getApiKey)
                        .ne(AiProviderDO::getApiKey, "")
        );
        if (activeProviders.isEmpty()) {
            return List.of();
        }
        List<String> activeProviderIds = activeProviders.stream()
                .map(AiProviderDO::getId)
                .toList();

        LambdaQueryWrapper<AiModelDO> wrapper = new LambdaQueryWrapper<AiModelDO>()
                .eq(AiModelDO::getEnabled, 1)
                .in(AiModelDO::getProviderId, activeProviderIds)
                .eq(StrUtil.isNotBlank(capability), AiModelDO::getCapability, capability != null ? capability.toUpperCase() : null)
                .orderByAsc(AiModelDO::getCapability)
                .orderByAsc(AiModelDO::getPriority);

        List<AiModelDO> models = modelMapper.selectList(wrapper);

        // 预加载供应商名称映射
        Map<String, String> providerNames = loadProviderNameMap();

        return models.stream()
                .map(m -> toModelVO(m, providerNames))
                .collect(Collectors.toList());
    }

    // ==================== 默认模型 & 优先级 ====================

    @Override
    @Transactional
    public void setDefaultModel(String id) {
        AiModelDO model = modelMapper.selectById(id);
        if (model == null) {
            throw new ClientException("模型不存在：" + id);
        }

        // 取消同 capability 下的其他默认模型
        clearDefaultForCapability(model.getCapability(), id);

        // 设置当前模型为默认
        model.setIsDefault(1);
        modelMapper.updateById(model);
        log.info("设置默认模型: modelId={}, capability={}", model.getModelId(), model.getCapability());

        configCache.reload();
    }

    @Override
    @Transactional
    public void updatePriorities(List<ModelPriorityItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        for (ModelPriorityItem item : items) {
            if (StrUtil.isBlank(item.getId()) || item.getPriority() == null) {
                continue;
            }
            modelMapper.update(null,
                    new LambdaUpdateWrapper<AiModelDO>()
                            .eq(AiModelDO::getId, item.getId())
                            .set(AiModelDO::getPriority, item.getPriority())
            );
        }
        log.info("批量更新模型优先级: count={}", items.size());

        configCache.reload();
    }

    // ==================== 内部方法 ====================

    /**
     * 清除指定 capability 下的默认模型标记（排除指定 ID）
     */
    private void clearDefaultForCapability(String capability, String excludeId) {
        modelMapper.update(null,
                new LambdaUpdateWrapper<AiModelDO>()
                        .eq(AiModelDO::getCapability, capability)
                        .eq(AiModelDO::getIsDefault, 1)
                        .ne(excludeId != null, AiModelDO::getId, excludeId)
                        .set(AiModelDO::getIsDefault, 0)
        );
    }

    /**
     * 清除指定供应商下所有模型的 Spring AI 实例缓存
     */
    private void evictModelsByProvider(String providerId) {
        List<AiModelDO> models = modelMapper.selectList(
                new LambdaQueryWrapper<AiModelDO>().eq(AiModelDO::getProviderId, providerId)
        );
        for (AiModelDO m : models) {
            chatModelFactory.evict(m.getModelId());
        }
    }

    // 将供应商 DO 转换为 VO，并解析 endpoints JSON 字段
    private AiProviderVO toProviderVO(AiProviderDO provider) {
        AiProviderVO vo = BeanUtil.toBean(provider, AiProviderVO.class,
                CopyOptions.create().setIgnoreProperties("endpoints"));
        // 解析 endpoints JSON
        vo.setEndpoints(parseEndpoints(provider.getEndpoints()));
        // 统计该供应商下的模型数量
        Long modelCount = modelMapper.selectCount(
                new LambdaQueryWrapper<AiModelDO>()
                        .eq(AiModelDO::getProviderId, provider.getId())
        );
        vo.setModelCount(modelCount.intValue());
        // 将 s3:// 内部 URL 转换为前端可访问的 HTTP URL
        if (StrUtil.isNotBlank(vo.getIconUrl()) && vo.getIconUrl().startsWith("s3://")) {
            try {
                vo.setIconUrl(fileStorageService.generatePresignedGetUrl(vo.getIconUrl()));
            } catch (Exception e) {
                log.warn("转换图标 URL 失败: {}", vo.getIconUrl(), e);
            }
        }
        return vo;
    }

    // 将模型 DO 转换为 VO，自动加载供应商名称
    private AiModelVO toModelVO(AiModelDO model) {
        return toModelVO(model, loadProviderNameMap());
    }

    // 将模型 DO 转换为 VO，使用预加载的供应商名称映射填充 providerName
    private AiModelVO toModelVO(AiModelDO model, Map<String, String> providerNames) {
        AiModelVO vo = BeanUtil.copyProperties(model, AiModelVO.class);
        vo.setProviderName(providerNames.getOrDefault(model.getProviderId(), ""));
        vo.setDimension(parseDimensionList(model.getDimension()));
        return vo;
    }

    // 加载所有供应商的 ID -> 名称 映射，用于模型列表展示供应商名称
    private Map<String, String> loadProviderNameMap() {
        List<AiProviderDO> providers = providerMapper.selectList(null);
        Map<String, String> map = new HashMap<>();
        for (AiProviderDO p : providers) {
            map.put(p.getId(), p.getName());
        }
        return map;
    }


    // 将 endpoints Map 序列化为 JSON 字符串，用于数据库存储
    /**
     * 为已知供应商补充默认 endpoint（仅在用户未设置时填充）
     */
    private Map<String, String> fillDefaultEndpoints(String providerName, Map<String, String> endpoints) {
        Map<String, String> result = endpoints != null ? new HashMap<>(endpoints) : new HashMap<>();
        String name = providerName.toLowerCase();
        if (name.contains("bailian") || name.contains("百炼")) {
            result.putIfAbsent("chat", "/compatible-mode/v1/chat/completions");
            result.putIfAbsent("embedding", "/compatible-mode/v1/embeddings");
            result.putIfAbsent("models", "/compatible-mode/v1/models");
        } else if (name.contains("siliconflow")) {
            result.putIfAbsent("chat", "/v1/chat/completions");
            result.putIfAbsent("embedding", "/v1/embeddings");
            result.putIfAbsent("models", "/v1/models");
        } else if (name.contains("deepseek")) {
            result.putIfAbsent("chat", "/v1/chat/completions");
            result.putIfAbsent("models", "/v1/models");
        } else if (name.contains("bigmodel") || name.contains("zhipu") || name.contains("智谱")) {
            result.putIfAbsent("chat", "/api/paas/v4/chat/completions");
            result.putIfAbsent("embedding", "/api/paas/v4/embeddings");
            result.putIfAbsent("models", "/api/paas/v4/models");
        }
        return result;
    }

    private String serializeEndpoints(Map<String, String> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(endpoints);
        } catch (JsonProcessingException e) {
            throw new ServiceException("序列化 endpoints 失败");
        }
    }

    // 将 JSON 字符串解析为 endpoints Map，解析失败时返回空 Map
    private Map<String, String> parseEndpoints(String json) {
        if (StrUtil.isBlank(json)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("解析 endpoints JSON 失败: {}", json, e);
            return new HashMap<>();
        }
    }

    // ==================== 连通性检查 & 远程模型 ====================

    @Override
    public ConnectivityResultVO checkConnectivity(String providerId) {
        AiProviderDO provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new ClientException("供应商不存在：" + providerId);
        }

        ProviderAdapter adapter = adapterRegistry.getAdapter(provider.getName());
        if (adapter == null) {
            throw new ServiceException("不支持的供应商类型：" + provider.getName());
        }

        Map<String, String> endpoints = parseEndpoints(provider.getEndpoints());

        log.info("检查供应商连通性: name={}, adapter={}", provider.getName(), adapter.getClass().getSimpleName());
        ProviderAdapter.ConnectivityResult result = adapter.checkConnectivity(
                provider.getBaseUrl(),
                provider.getApiKey(),
                endpoints
        );

        ConnectivityResultVO vo = new ConnectivityResultVO();
        vo.setSuccess(result.success());
        vo.setLatencyMs(result.latencyMs());
        vo.setError(result.error());
        return vo;
    }

    // 用于模型连通性检查的 HTTP 客户端
    private final HttpClient modelCheckHttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public ConnectivityResultVO checkModelConnectivity(String id) {
        AiModelDO model = modelMapper.selectById(id);
        // Fallback: 按 modelId 字符串查找（如 "BAAI/bge-m3"）
        if (model == null) {
            model = modelMapper.selectOne(
                    new LambdaQueryWrapper<AiModelDO>()
                            .eq(AiModelDO::getModelId, id)
                            .last("LIMIT 1")
            );
        }
        if (model == null) {
            throw new ClientException("模型不存在：" + id);
        }
        AiProviderDO provider = providerMapper.selectById(model.getProviderId());
        if (provider == null) {
            throw new ClientException("供应商不存在：" + model.getProviderId());
        }

        // 优先使用模型的自定义 URL
        String baseUrl = StrUtil.isNotBlank(model.getCustomUrl())
                ? model.getCustomUrl()
                : provider.getBaseUrl();
        if (StrUtil.isBlank(baseUrl)) {
            return new ConnectivityResultVO(false, null, "供应商未配置 API 地址");
        }

        String apiKey = provider.getApiKey();
        if (StrUtil.isBlank(apiKey)) {
            return new ConnectivityResultVO(false, null, "供应商未配置 API Key");
        }

        // 兼容 capability 为空的情况
        String capability = model.getCapability();
        if (StrUtil.isBlank(capability)) {
            log.warn("模型 {} 能力类型为空，使用 CHAT 兜底", model.getModelId());
            capability = "CHAT";
        }

        Map<String, String> endpoints = parseEndpoints(provider.getEndpoints());

        log.info("检查模型连通性: modelId={}, capability={}, baseUrl={}",
                model.getModelId(), capability, baseUrl);

        return switch (capability.toUpperCase()) {
            case "CHAT" -> checkChatModelConnectivity(baseUrl, apiKey, model.getModelId(), endpoints);
            case "EMBEDDING" -> checkEmbeddingModelConnectivity(model, provider, baseUrl, apiKey, endpoints);
            case "RERANK" -> checkRerankModelConnectivity(model, provider, baseUrl, apiKey, endpoints);
            default -> {
                log.warn("未知的模型能力类型: {}，使用 CHAT 方式兜底", model.getCapability());
                yield checkChatModelConnectivity(baseUrl, apiKey, model.getModelId(), endpoints);
            }
        };
    }

    /**
     * 检查 CHAT 模型的连通性：发送一个轻量级的 chat completion 请求
     */
    private ConnectivityResultVO checkChatModelConnectivity(String baseUrl, String apiKey, String modelId,
                                                            Map<String, String> endpoints) {
        Instant start = Instant.now();
        try {
            String url = resolveEndpointUrl(baseUrl, endpoints, "chat", "/v1/chat/completions");
            String body = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"test\"}],\"max_tokens\":1,\"stream\":false}",
                    modelId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = modelCheckHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            if (response.statusCode() == 200) {
                return new ConnectivityResultVO(true, latencyMs, null);
            } else {
                String errorMsg = extractError(response.body());
                return new ConnectivityResultVO(false, latencyMs, "HTTP " + response.statusCode() + ": " + errorMsg);
            }
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            return new ConnectivityResultVO(false, latencyMs, e.getMessage());
        }
    }

    /**
     * 检查 EMBEDDING 模型的连通性
     */
    private ConnectivityResultVO checkEmbeddingModelConnectivity(AiModelDO model, AiProviderDO provider,
                                                                  String baseUrl, String apiKey,
                                                                  Map<String, String> endpoints) {
        Instant start = Instant.now();
        try {
            String protocol = StrUtil.isNotBlank(model.getApiProtocol())
                    ? model.getApiProtocol()
                    : StrUtil.isNotBlank(provider.getApiProtocol())
                            ? provider.getApiProtocol()
                            : "openai";

            DynamicModelConfig.ModelEntry entry = DynamicModelConfig.ModelEntry.builder()
                    .id(model.getModelId()).provider(provider.getName()).model(model.getModelId())
                    .protocol(protocol).build();
            DynamicModelConfig.ProviderEntry providerEntry = DynamicModelConfig.ProviderEntry.builder()
                    .name(provider.getName()).url(baseUrl).apiKey(apiKey)
                    .endpoints(endpoints != null ? endpoints : new HashMap<>())
                    .protocol(protocol).build();
            ModelTarget target = new ModelTarget(model.getModelId(), entry, providerEntry);

            ModelProtocol proto = protocolRegistry.get(target.protocolName());
            String url = chatModelFactory.resolveEmbeddingUrl(target);

            Object requestBody = proto.buildEmbeddingRequest(model.getModelId(), List.of("test"), null);
            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(proto.authHeaderName(), proto.authHeaderValue(apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = modelCheckHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            if (response.statusCode() == 200) {
                return new ConnectivityResultVO(true, latencyMs, null);
            } else {
                String errorMsg = extractError(response.body());
                return new ConnectivityResultVO(false, latencyMs, "HTTP " + response.statusCode() + ": " + errorMsg);
            }
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            return new ConnectivityResultVO(false, latencyMs, e.getMessage());
        }
    }

    /**
     * 检查 RERANK 模型的连通性：发送一个轻量级的 rerank 请求，协议感知
     */
    private ConnectivityResultVO checkRerankModelConnectivity(AiModelDO model, AiProviderDO provider,
                                                              String baseUrl, String apiKey,
                                                              Map<String, String> endpoints) {
        Instant start = Instant.now();
        try {
            String protocolName = StrUtil.isNotBlank(model.getApiProtocol())
                    ? model.getApiProtocol()
                    : StrUtil.isNotBlank(provider.getApiProtocol())
                            ? provider.getApiProtocol()
                            : "openai";

            ModelProtocol proto = protocolRegistry.get(protocolName);
            String url = proto.resolveRerankUrl(baseUrl);

            Map<String, Object> requestBody = proto.buildRerankRequest(
                    model.getModelId(), "test", List.of("test document"));
            String body = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header(proto.authHeaderName(), proto.authHeaderValue(apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = modelCheckHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = Duration.between(start, Instant.now()).toMillis();

            if (response.statusCode() == 200) {
                return new ConnectivityResultVO(true, latencyMs, null);
            } else {
                String errorMsg = extractError(response.body());
                return new ConnectivityResultVO(false, latencyMs, "HTTP " + response.statusCode() + ": " + errorMsg);
            }
        } catch (Exception e) {
            long latencyMs = Duration.between(start, Instant.now()).toMillis();
            return new ConnectivityResultVO(false, latencyMs, e.getMessage());
        }
    }

    // 将向量维度列表序列化为 JSON 字符串，如 [1024, 1536, 4096]
    private String serializeDimension(List<Integer> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(dimensions);
        } catch (JsonProcessingException e) {
            throw new ServiceException("序列化 dimension 失败");
        }
    }

    // 将 JSON 字符串解析为向量维度列表，解析失败时返回 null
    private List<Integer> parseDimensionList(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            if (json.trim().startsWith("[")) {
                return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {});
            }
            int single = Integer.parseInt(json.trim());
            return List.of(single);
        } catch (Exception e) {
            log.warn("解析 dimension 失败: {}", json, e);
            return null;
        }
    }

    /**
     * 从错误响应中提取错误信息
     */
    private String extractError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "unknown error";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
            com.fasterxml.jackson.databind.JsonNode error = root.get("error");
            if (error != null) {
                if (error.has("message")) {
                    return error.get("message").asText();
                }
                return error.toString();
            }
        } catch (Exception ignored) {
        }
        return responseBody.length() > 100 ? responseBody.substring(0, 100) : responseBody;
    }

    /**
     * 根据供应商端点配置解析完整 URL
     * <p>
     * 优先使用 endpoints 中的自定义路径，未配置时使用默认路径 + normalizeUrl。
     * 例如：endpoints 中配置 "chat":"/compatible-mode/v1/chat/completions"，
     * 则直接拼接 baseUrl + /compatible-mode/v1/chat/completions。
     * 未配置时使用默认路径 /v1/chat/completions。
     * </p>
     */
    private String resolveEndpointUrl(String baseUrl, Map<String, String> endpoints, String key, String defaultPath) {
        if (endpoints != null && endpoints.containsKey(key)) {
            return joinUrl(baseUrl, endpoints.get(key));
        }
        return joinUrl(baseUrl, defaultPath);
    }

    /**
     * 拼接基础 URL 和路径，自动处理斜杠分隔
     */
    private static String joinUrl(String baseUrl, String path) {
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    @Override
    public FetchModelsResultVO fetchRemoteModels(String providerId) {
        AiProviderDO provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new ClientException("供应商不存在：" + providerId);
        }

        ProviderAdapter adapter = adapterRegistry.getAdapter(provider.getName());
        if (adapter == null) {
            throw new ServiceException("不支持的供应商类型：" + provider.getName());
        }

        Map<String, String> endpoints = parseEndpoints(provider.getEndpoints());

        log.info("拉取远程模型列表: name={}, adapter={}", provider.getName(), adapter.getClass().getSimpleName());
        List<ProviderAdapter.RemoteModelInfo> remoteModels = adapter.fetchModels(
                provider.getBaseUrl(),
                provider.getApiKey(),
                endpoints
        );

        List<RemoteModelInfoVO> voList = remoteModels.stream()
                .map(m -> new RemoteModelInfoVO(
                        m.modelId(),
                        m.modelName(),
                        m.capabilities(),
                        m.supportsThinking(),
                        m.supportsMultimodal(),
                        m.dimensions()
                ))
                .collect(Collectors.toList());

        return new FetchModelsResultVO(voList);
    }

    @Override
    public List<String> batchCreateModels(List<AiModelCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        List<String> ids = new java.util.ArrayList<>();
        for (AiModelCreateRequest request : requests) {
            if (StrUtil.isBlank(request.getProviderId())
                    || StrUtil.isBlank(request.getModelId())
                    || StrUtil.isBlank(request.getModelName())) {
                log.warn("批量创建模型跳过无效请求: {}", request);
                continue;
            }

            // 校验是否已存在
            AiModelDO existing = modelMapper.selectOne(
                    new LambdaQueryWrapper<AiModelDO>()
                            .eq(AiModelDO::getModelId, request.getModelId())
                            .eq(AiModelDO::getProviderId, request.getProviderId())
                            .last("LIMIT 1")
            );
            if (existing != null) {
                if (existing.getEnabled() == 1) {
                    log.info("模型已存在且已启用，跳过: providerId={}, modelId={}", request.getProviderId(), request.getModelId());
                    continue;
                }
                // 已禁用 → 重新启用并更新信息
                AiModelDO update = new AiModelDO();
                update.setId(existing.getId());
                update.setEnabled(1);
                update.setModelName(request.getModelName().trim());
                update.setCapability(request.getCapability().toUpperCase());
                update.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : 0);
                update.setPriority(request.getPriority() != null ? request.getPriority() : 100);
                update.setSupportsThinking(request.getSupportsThinking() != null ? request.getSupportsThinking() : 0);
                update.setSupportsMultimodal(request.getSupportsMultimodal() != null ? request.getSupportsMultimodal() : 0);
                update.setDimension(serializeDimension(request.getDimension()));
                if (request.getCustomUrl() != null) {
                    update.setCustomUrl(request.getCustomUrl());
                }
                if (request.getApiProtocol() != null) {
                    update.setApiProtocol(request.getApiProtocol());
                }
                modelMapper.updateById(update);
                ids.add(existing.getId());
                log.info("模型已重新启用: id={}, modelId={}", existing.getId(), request.getModelId());
                continue;
            }

            try {
                // 直接创建，不调用 @Transactional 的 createModel 以避免事务回滚问题
                AiProviderDO provider = providerMapper.selectById(request.getProviderId());
                if (provider == null) {
                    log.warn("供应商不存在，跳过: {}", request.getProviderId());
                    continue;
                }

                AiModelDO model = AiModelDO.builder()
                        .providerId(request.getProviderId())
                        .modelId(request.getModelId().trim())
                        .modelName(request.getModelName().trim())
                        .capability(request.getCapability().toUpperCase())
                        .isDefault(request.getIsDefault() != null ? request.getIsDefault() : 0)
                        .priority(request.getPriority() != null ? request.getPriority() : 100)
                        .enabled(request.getEnabled() != null ? request.getEnabled() : 1)
                        .supportsThinking(request.getSupportsThinking() != null ? request.getSupportsThinking() : 0)
                        .supportsMultimodal(request.getSupportsMultimodal() != null ? request.getSupportsMultimodal() : 0)
                        .dimension(serializeDimension(request.getDimension()))
                        .customUrl(request.getCustomUrl())
                        .apiProtocol(request.getApiProtocol())
                        .build();

                modelMapper.insert(model);
                ids.add(model.getId());
                log.info("批量创建模型: id={}, modelId={}, capability={}", model.getId(), model.getModelId(), model.getCapability());
            } catch (Exception e) {
                log.warn("批量创建模型失败: modelId={}, error={}", request.getModelId(), e.getMessage());
            }
        }

        log.info("批量创建模型完成: 请求={} 成功={}", requests.size(), ids.size());
        configCache.reload();
        return ids;
    }

    // ==================== 图标管理 ====================

    @Override
    public void updateProviderIcon(String providerId, String iconUrl) {
        AiProviderDO existing = providerMapper.selectById(providerId);
        if (existing == null) {
            throw new ClientException("供应商不存在：" + providerId);
        }

        existing.setIconUrl(iconUrl);
        providerMapper.updateById(existing);
        log.info("更新供应商图标: id={}, iconUrl={}", providerId, iconUrl);

        configCache.reload();
    }
}
