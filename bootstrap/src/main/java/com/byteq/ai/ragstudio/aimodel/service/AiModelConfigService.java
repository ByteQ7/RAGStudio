package com.byteq.ai.ragstudio.aimodel.service;

import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.ModelPriorityItem;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiModelVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiProviderVO;

import java.util.List;

/**
 * AI 模型配置管理服务接口
 * <p>
 * 提供 AI 供应商和模型的完整生命周期管理，包括 CRUD、默认模型设置、优先级调整等。
 * 每次写操作完成后自动刷新配置缓存，使变更实时生效。
 * </p>
 */
public interface AiModelConfigService {

    // ==================== 供应商管理 ====================

    String createProvider(AiProviderCreateRequest request);

    void updateProvider(String id, AiProviderUpdateRequest request);

    void deleteProvider(String id);

    AiProviderVO getProvider(String id);

    List<AiProviderVO> listProviders();

    // ==================== 模型管理 ====================

    String createModel(AiModelCreateRequest request);

    void updateModel(String id, AiModelUpdateRequest request);

    void deleteModel(String id);

    AiModelVO getModel(String id);

    List<AiModelVO> listModels(String capability);

    // ==================== 默认模型 & 优先级 ====================

    /**
     * 设置指定 capability 下的默认模型
     *
     * @param id 模型 ID（数据库主键）
     */
    void setDefaultModel(String id);

    /**
     * 批量调整模型优先级
     *
     * @param items 优先级调整列表
     */
    void updatePriorities(List<ModelPriorityItem> items);
}
