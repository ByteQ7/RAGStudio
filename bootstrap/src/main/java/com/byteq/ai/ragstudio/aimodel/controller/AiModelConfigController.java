package com.byteq.ai.ragstudio.aimodel.controller;

import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.ModelPriorityItem;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiModelVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiProviderVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.ConnectivityResultVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.FetchModelsResultVO;
import com.byteq.ai.ragstudio.aimodel.service.AiModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.constant.RAGConstant;
import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import com.byteq.ai.ragstudio.framework.web.Results;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * AI 模型配置管理控制器
 * <p>
 * 提供 AI 供应商和模型的增删改查接口，以及默认模型设置和优先级调整。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/ai-model-config")
@RequiredArgsConstructor
public class AiModelConfigController {

    private final AiModelConfigService aiModelConfigService;
    private final FileStorageService fileStorageService;

    // ==================== 供应商接口 ====================

    /**
     * 查询所有 AI 供应商列表
     *
     * @return 供应商列表
     */
    @GetMapping("/providers")
    public Result<List<AiProviderVO>> listProviders() {
        return Results.success(aiModelConfigService.listProviders());
    }

    /**
     * 根据 ID 查询供应商详情
     *
     * @param id 供应商 ID
     * @return 供应商信息
     */
    @GetMapping("/providers/{id}")
    public Result<AiProviderVO> getProvider(@PathVariable("id") String id) {
        return Results.success(aiModelConfigService.getProvider(id));
    }

    /**
     * 创建新的 AI 供应商
     *
     * @param request 创建请求参数
     * @return 新创建的供应商 ID
     */
    @PostMapping("/providers")
    public Result<String> createProvider(@Valid @RequestBody AiProviderCreateRequest request) {
        return Results.success(aiModelConfigService.createProvider(request));
    }

    /**
     * 更新指定供应商的信息
     *
     * @param id      供应商 ID
     * @param request 更新请求参数
     * @return 操作结果
     */
    @PutMapping("/providers/{id}")
    public Result<Void> updateProvider(@PathVariable("id") String id,
                                       @RequestBody AiProviderUpdateRequest request) {
        aiModelConfigService.updateProvider(id, request);
        return Results.success();
    }

    /**
     * 删除指定供应商（需先禁用或删除其下的模型）
     *
     * @param id 供应商 ID
     * @return 操作结果
     */
    @DeleteMapping("/providers/{id}")
    public Result<Void> deleteProvider(@PathVariable("id") String id) {
        aiModelConfigService.deleteProvider(id);
        return Results.success();
    }

    // ==================== 模型接口 ====================

    /**
     * 查询模型列表，可按能力类型（capability）过滤
     *
     * @param capability 能力类型过滤条件（可选）
     * @return 模型列表
     */
    @GetMapping("/models")
    public Result<List<AiModelVO>> listModels(
            @RequestParam(value = "capability", required = false) String capability) {
        return Results.success(aiModelConfigService.listModels(capability));
    }

    /**
     * 根据 ID 查询模型详情
     *
     * @param id 模型 ID
     * @return 模型信息
     */
    @GetMapping("/models/{id}")
    public Result<AiModelVO> getModel(@PathVariable("id") String id) {
        return Results.success(aiModelConfigService.getModel(id));
    }

    /**
     * 创建新的 AI 模型配置
     *
     * @param request 创建请求参数
     * @return 新创建的模型 ID
     */
    @PostMapping("/models")
    public Result<String> createModel(@Valid @RequestBody AiModelCreateRequest request) {
        return Results.success(aiModelConfigService.createModel(request));
    }

    /**
     * 更新指定模型的配置信息
     *
     * @param id      模型 ID
     * @param request 更新请求参数
     * @return 操作结果
     */
    @PutMapping("/models/{id}")
    public Result<Void> updateModel(@PathVariable("id") String id,
                                    @RequestBody AiModelUpdateRequest request) {
        aiModelConfigService.updateModel(id, request);
        return Results.success();
    }

    /**
     * 删除指定模型配置
     *
     * @param id 模型 ID
     * @return 操作结果
     */
    @DeleteMapping("/models/{id}")
    public Result<Void> deleteModel(@PathVariable("id") String id) {
        aiModelConfigService.deleteModel(id);
        return Results.success();
    }

    // ==================== 默认模型 & 优先级 ====================

    /**
     * 将指定模型设置为同能力类型下的默认模型
     *
     * @param id 模型 ID
     * @return 操作结果
     */
    @PatchMapping("/models/{id}/set-default")
    public Result<Void> setDefaultModel(@PathVariable("id") String id) {
        aiModelConfigService.setDefaultModel(id);
        return Results.success();
    }

    /**
     * 批量调整模型优先级
     *
     * @param items 优先级调整列表
     * @return 操作结果
     */
    @PatchMapping("/models/priorities")
    public Result<Void> updatePriorities(@Valid @RequestBody List<ModelPriorityItem> items) {
        aiModelConfigService.updatePriorities(items);
        return Results.success();
    }

    // ==================== 连通性检查 & 远程模型 ====================

    /**
     * 检查指定 AI 供应商的连通性
     * <p>
     * 根据供应商类型自动选择对应的适配器，发送空对话到供应商 API 验证连接是否正常。
     * </p>
     *
     * @param id 供应商 ID
     * @return 连通性检查结果（成功/失败、延迟、错误信息）
     */
    @PostMapping("/providers/{id}/check-connectivity")
    public Result<ConnectivityResultVO> checkConnectivity(@PathVariable("id") String id) {
        return Results.success(aiModelConfigService.checkConnectivity(id));
    }

    /**
     * 从远程供应商拉取可用模型列表
     * <p>
     * 根据供应商类型自动选择对应的适配器，调用供应商的模型列表 API，
     * 获取其支持的所有模型信息。
     * </p>
     *
     * @param id 供应商 ID
     * @return 远程模型列表
     */
    @PostMapping("/providers/{id}/fetch-models")
    public Result<FetchModelsResultVO> fetchRemoteModels(@PathVariable("id") String id) {
        return Results.success(aiModelConfigService.fetchRemoteModels(id));
    }

    /**
     * 检查指定 AI 模型的连通性
     * <p>
     * 根据模型的能力类型（CHAT/EMBEDDING/RERANK）发送对应的轻量 API 请求，
     * 验证该模型是否可正常调用。支持模型自定义 URL。
     * </p>
     *
     * @param id 模型 ID
     * @return 连通性检查结果
     */
    @PostMapping("/models/{id}/check-connectivity")
    public Result<ConnectivityResultVO> checkModelConnectivity(@PathVariable("id") String id) {
        return Results.success(aiModelConfigService.checkModelConnectivity(id));
    }

    /**
     * 批量创建模型
     * <p>
     * 用于从远程拉取模型列表后批量导入，会自动跳过已存在的模型。
     * </p>
     *
     * @param requests 模型创建请求列表
     * @return 新创建的模型 ID 列表
     */
    @PostMapping("/models/batch-create")
    public Result<List<String>> batchCreateModels(@RequestBody List<AiModelCreateRequest> requests) {
        return Results.success(aiModelConfigService.batchCreateModels(requests));
    }

    // ==================== 图标上传 ====================

    /**
     * 上传 AI 供应商图标
     * <p>
     * 将图标文件上传到 S3 ragstudio/AIProviderIcons/ 目录，
     * 并更新供应商的 icon_url 字段。
     * </p>
     *
     * @param id   供应商 ID
     * @param file 图标文件（支持 PNG/JPG/SVG）
     * @return 图标 URL
     */
    @PostMapping("/providers/{id}/icon")
    public Result<Map<String, String>> uploadIcon(
            @PathVariable("id") String id,
            @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ClientException("上传文件不能为空");
        }

        // 上传到 S3
        StoredFileDTO stored = fileStorageService.upload(
                RAGConstant.S3_BUCKET_NAME,
                RAGConstant.S3_AI_PROVIDER_ICON_PREFIX,
                file);

        // 更新供应商的 icon_url
        aiModelConfigService.updateProviderIcon(id, stored.getUrl());

        return Results.success(Map.of("iconUrl", stored.getUrl()));
    }
}
