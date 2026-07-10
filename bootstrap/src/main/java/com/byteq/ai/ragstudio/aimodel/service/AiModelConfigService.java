package com.byteq.ai.ragstudio.aimodel.service;

import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.ModelPriorityItem;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiModelVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiProviderVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.ConnectivityResultVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.FetchModelsResultVO;

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

    /**
     * 创建 AI 供应商
     *
     * @param request 创建请求参数
     * @return 新创建的供应商 ID
     */
    String createProvider(AiProviderCreateRequest request);

    /**
     * 更新指定供应商的信息
     *
     * @param id      供应商 ID
     * @param request 更新请求参数
     */
    void updateProvider(String id, AiProviderUpdateRequest request);

    /**
     * 删除指定供应商（需先处理其下已启用的模型）
     *
     * @param id 供应商 ID
     */
    void deleteProvider(String id);

    /**
     * 根据 ID 查询供应商详情
     *
     * @param id 供应商 ID
     * @return 供应商信息
     */
    AiProviderVO getProvider(String id);

    /**
     * 查询所有供应商列表
     *
     * @return 供应商列表
     */
    List<AiProviderVO> listProviders();

    // ==================== 模型管理 ====================

    /**
     * 创建 AI 模型配置
     *
     * @param request 创建请求参数
     * @return 新创建的模型 ID
     */
    String createModel(AiModelCreateRequest request);

    /**
     * 更新指定模型的配置信息
     *
     * @param id      模型 ID
     * @param request 更新请求参数
     */
    void updateModel(String id, AiModelUpdateRequest request);

    /**
     * 删除指定模型配置
     *
     * @param id 模型 ID
     */
    void deleteModel(String id);

    /**
     * 根据 ID 查询模型详情
     *
     * @param id 模型 ID
     * @return 模型信息
     */
    AiModelVO getModel(String id);

    /**
     * 查询模型列表，可按能力类型过滤
     *
     * @param capability 能力类型（可选）
     * @return 模型列表
     */
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

    // ==================== 连通性检查 & 远程模型 ====================

    /**
     * 检查指定供应商的连通性
     *
     * @param providerId 供应商 ID
     * @return 连通性检查结果
     */
    ConnectivityResultVO checkConnectivity(String providerId);

    /**
     * 从远程供应商拉取模型列表
     *
     * @param providerId 供应商 ID
     * @return 远程模型列表
     */
    FetchModelsResultVO fetchRemoteModels(String providerId);

    /**
     * 批量创建模型
     *
     * @param requests 模型创建请求列表
     * @return 新创建的模型 ID 列表
     */
    List<String> batchCreateModels(List<AiModelCreateRequest> requests);

    // ==================== 图标管理 ====================

    /**
     * 更新供应商图标 URL
     *
     * @param providerId 供应商 ID
     * @param iconUrl    图标 URL
     */
    void updateProviderIcon(String providerId, String iconUrl);
}
