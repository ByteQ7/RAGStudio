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
import com.byteq.ai.ragstudio.infra.springai.SpringAiChatModelFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final SpringAiChatModelFactory chatModelFactory;
    private final ObjectMapper objectMapper;
    private final ProviderAdapterRegistry adapterRegistry;
    private final FileStorageService fileStorageService;

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

        AiProviderDO provider = AiProviderDO.builder()
                .name(request.getName().trim())
                .displayName(request.getDisplayName())
                .baseUrl(request.getBaseUrl().trim())
                .apiKey(request.getApiKey())
                .endpoints(serializeEndpoints(request.getEndpoints()))
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
            existing.setEndpoints(serializeEndpoints(request.getEndpoints()));
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
                .dimension(request.getDimension())
                .customUrl(request.getCustomUrl())
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

        String oldProviderId = existing.getProviderId();

        if (StrUtil.isNotBlank(request.getProviderId()) && !request.getProviderId().equals(oldProviderId)) {
            AiProviderDO provider = providerMapper.selectById(request.getProviderId());
            if (provider == null) {
                throw new ClientException("供应商不存在：" + request.getProviderId());
            }
            existing.setProviderId(request.getProviderId());
        }
        if (StrUtil.isNotBlank(request.getModelName())) {
            existing.setModelName(request.getModelName().trim());
        }
        if (StrUtil.isNotBlank(request.getCapability())) {
            existing.setCapability(request.getCapability().toUpperCase());
        }
        if (request.getPriority() != null) {
            existing.setPriority(request.getPriority());
        }
        if (request.getEnabled() != null) {
            existing.setEnabled(request.getEnabled());
        }
        if (request.getSupportsThinking() != null) {
            existing.setSupportsThinking(request.getSupportsThinking());
        }
        if (request.getSupportsMultimodal() != null) {
            existing.setSupportsMultimodal(request.getSupportsMultimodal());
        }
        if (request.getDimension() != null) {
            existing.setDimension(request.getDimension());
        }
        if (request.getCustomUrl() != null) {
            existing.setCustomUrl(request.getCustomUrl());
        }

        modelMapper.updateById(existing);
        log.info("更新 AI 模型: id={}, modelId={}", id, existing.getModelId());

        // 清除该模型的 Spring AI 实例缓存
        chatModelFactory.evict(existing.getModelId());
        configCache.reload();
    }

    @Override
    public void deleteModel(String id) {
        AiModelDO existing = modelMapper.selectById(id);
        if (existing == null) {
            throw new ClientException("模型不存在：" + id);
        }

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
        LambdaQueryWrapper<AiModelDO> wrapper = new LambdaQueryWrapper<AiModelDO>()
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

        log.info("检查供应商连通性: name={}, adapter={}", provider.getName(), adapter.getClass().getSimpleName());
        ProviderAdapter.ConnectivityResult result = adapter.checkConnectivity(
                provider.getBaseUrl(),
                provider.getApiKey()
        );

        ConnectivityResultVO vo = new ConnectivityResultVO();
        vo.setSuccess(result.success());
        vo.setLatencyMs(result.latencyMs());
        vo.setError(result.error());
        return vo;
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

        log.info("拉取远程模型列表: name={}, adapter={}", provider.getName(), adapter.getClass().getSimpleName());
        List<ProviderAdapter.RemoteModelInfo> remoteModels = adapter.fetchModels(
                provider.getBaseUrl(),
                provider.getApiKey()
        );

        List<RemoteModelInfoVO> voList = remoteModels.stream()
                .map(m -> new RemoteModelInfoVO(
                        m.modelId(),
                        m.modelName(),
                        m.capabilities(),
                        m.supportsThinking(),
                        m.supportsMultimodal(),
                        m.dimension()
                ))
                .collect(Collectors.toList());

        return new FetchModelsResultVO(voList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> batchCreateModels(List<AiModelCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        List<String> ids = new java.util.ArrayList<>();
        for (AiModelCreateRequest request : requests) {
            // 复用单个创建的校验逻辑，但不重复抛出异常，跳过已存在的模型
            if (StrUtil.isBlank(request.getProviderId())
                    || StrUtil.isBlank(request.getModelId())
                    || StrUtil.isBlank(request.getModelName())) {
                log.warn("批量创建模型跳过无效请求: {}", request);
                continue;
            }

            // 校验是否已存在
            Long count = modelMapper.selectCount(
                    new LambdaQueryWrapper<AiModelDO>()
                            .eq(AiModelDO::getModelId, request.getModelId())
                            .eq(AiModelDO::getProviderId, request.getProviderId())
            );
            if (count > 0) {
                log.info("模型已存在，跳过: providerId={}, modelId={}", request.getProviderId(), request.getModelId());
                continue;
            }

            try {
                String id = createModel(request);
                ids.add(id);
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
