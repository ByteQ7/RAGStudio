package com.byteq.ai.ragstudio.framework.convention;

/**
 * 场景默认模型查找接口
 * <p>供 RoutingLLMService 等基础设施模块获取业务层配置的默认模型 ID。</p>
 */
public interface DefaultModelService {

    /** 获取指定场景的默认模型 ID */
    String getDefaultModelId(String sceneKey);
}
