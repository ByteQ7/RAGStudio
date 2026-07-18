package com.byteq.ai.ragstudio.aimodel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.aimodel.controller.vo.DefaultModelConfigVO;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiModelDO;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiProviderDO;
import com.byteq.ai.ragstudio.aimodel.dao.entity.DefaultModelConfigDO;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiModelMapper;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiProviderMapper;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.DefaultModelConfigMapper;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 场景默认模型配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultModelConfigServiceImpl implements DefaultModelConfigService {

    private final DefaultModelConfigMapper mapper;
    private final AiModelMapper aiModelMapper;
    private final AiProviderMapper aiProviderMapper;

    @Override
    public List<DefaultModelConfigVO> listAll() {
        List<DefaultModelConfigDO> configs = mapper.selectList(null);
        return configs.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public DefaultModelConfigVO getConfig(String configKey) {
        DefaultModelConfigDO config = mapper.selectOne(
                new LambdaQueryWrapper<DefaultModelConfigDO>()
                        .eq(DefaultModelConfigDO::getConfigKey, configKey)
        );
        return config != null ? toVO(config) : null;
    }

    @Override
    public String getModelId(String configKey) {
        DefaultModelConfigDO config = mapper.selectOne(
                new LambdaQueryWrapper<DefaultModelConfigDO>()
                        .eq(DefaultModelConfigDO::getConfigKey, configKey)
        );
        return config != null ? config.getModelId() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(String configKey, String modelId) {
        // 校验 modelId 存在且启用
        AiModelDO model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModelDO>()
                        .eq(AiModelDO::getModelId, modelId)
                        .eq(AiModelDO::getEnabled, 1)
                        .last("LIMIT 1")
        );
        if (model == null) {
            throw new ClientException("模型不存在或已禁用：" + modelId);
        }

        DefaultModelConfigDO config = mapper.selectOne(
                new LambdaQueryWrapper<DefaultModelConfigDO>()
                        .eq(DefaultModelConfigDO::getConfigKey, configKey)
        );
        if (config == null) {
            throw new ClientException("配置项不存在：" + configKey);
        }

        config.setModelId(modelId);
        mapper.updateById(config);
        log.info("更新默认模型配置: configKey={}, modelId={}", configKey, modelId);
    }

    // ==================== 内部方法 ====================

    private DefaultModelConfigVO toVO(DefaultModelConfigDO config) {
        // 查询关联的模型信息
        AiModelDO model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModelDO>()
                        .eq(AiModelDO::getModelId, config.getModelId())
                        .last("LIMIT 1")
        );
        String modelName = model != null ? model.getModelName() : config.getModelId();
        Boolean modelEnabled = model != null ? (model.getEnabled() == 1) : false;

        // 查询供应商 API Key 状态
        Boolean hasApiKey = false;
        if (model != null) {
            AiProviderDO provider = aiProviderMapper.selectById(model.getProviderId());
            hasApiKey = provider != null
                    && provider.getApiKey() != null
                    && !provider.getApiKey().isEmpty();
        }

        String providerName = "";
        if (model != null) {
            AiProviderDO provider = aiProviderMapper.selectById(model.getProviderId());
            if (provider != null) {
                providerName = provider.getName();
            }
        }

        return DefaultModelConfigVO.builder()
                .id(config.getId())
                .configKey(config.getConfigKey())
                .modelId(config.getModelId())
                .modelName(modelName)
                .providerName(providerName)
                .modelEnabled(modelEnabled)
                .hasApiKey(hasApiKey)
                .build();
    }
}
