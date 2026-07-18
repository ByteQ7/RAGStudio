package com.byteq.ai.ragstudio.aimodel.service;

import com.byteq.ai.ragstudio.aimodel.controller.vo.DefaultModelConfigVO;

import java.util.List;

/**
 * 场景默认模型配置服务接口
 * <p>管理各场景（对话、摘要、标题、多模态、文档图片）的默认模型。</p>
 */
public interface DefaultModelConfigService {

    /**
     * 查询所有场景的默认模型配置
     */
    List<DefaultModelConfigVO> listAll();

    /**
     * 获取指定场景的默认模型配置
     *
     * @param configKey 配置键
     * @return 默认模型配置，若不存在返回 null
     */
    DefaultModelConfigVO getConfig(String configKey);

    /**
     * 获取指定场景的默认模型 ID
     *
     * @param configKey 配置键
     * @return modelId，若不存在返回 null
     */
    String getModelId(String configKey);

    /**
     * 更新指定场景的默认模型
     *
     * @param configKey 配置键
     * @param modelId   新的 modelId
     */
    void updateConfig(String configKey, String modelId);
}
