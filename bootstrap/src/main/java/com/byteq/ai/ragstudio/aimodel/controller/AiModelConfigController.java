package com.byteq.ai.ragstudio.aimodel.controller;

import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiModelUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderCreateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.AiProviderUpdateRequest;
import com.byteq.ai.ragstudio.aimodel.controller.request.ModelPriorityItem;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiModelVO;
import com.byteq.ai.ragstudio.aimodel.controller.vo.AiProviderVO;
import com.byteq.ai.ragstudio.aimodel.service.AiModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.Result;
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

import java.util.List;

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

    // ==================== 供应商接口 ====================

    @GetMapping("/providers")
    public Result<List<AiProviderVO>> listProviders() {
        return Results.success(aiModelConfigService.listProviders());
    }

    @GetMapping("/providers/{id}")
    public Result<AiProviderVO> getProvider(@PathVariable("id") String id) {
        return Results.success(aiModelConfigService.getProvider(id));
    }

    @PostMapping("/providers")
    public Result<String> createProvider(@Valid @RequestBody AiProviderCreateRequest request) {
        return Results.success(aiModelConfigService.createProvider(request));
    }

    @PutMapping("/providers/{id}")
    public Result<Void> updateProvider(@PathVariable("id") String id,
                                       @RequestBody AiProviderUpdateRequest request) {
        aiModelConfigService.updateProvider(id, request);
        return Results.success();
    }

    @DeleteMapping("/providers/{id}")
    public Result<Void> deleteProvider(@PathVariable("id") String id) {
        aiModelConfigService.deleteProvider(id);
        return Results.success();
    }

    // ==================== 模型接口 ====================

    @GetMapping("/models")
    public Result<List<AiModelVO>> listModels(
            @RequestParam(value = "capability", required = false) String capability) {
        return Results.success(aiModelConfigService.listModels(capability));
    }

    @GetMapping("/models/{id}")
    public Result<AiModelVO> getModel(@PathVariable("id") String id) {
        return Results.success(aiModelConfigService.getModel(id));
    }

    @PostMapping("/models")
    public Result<String> createModel(@Valid @RequestBody AiModelCreateRequest request) {
        return Results.success(aiModelConfigService.createModel(request));
    }

    @PutMapping("/models/{id}")
    public Result<Void> updateModel(@PathVariable("id") String id,
                                    @RequestBody AiModelUpdateRequest request) {
        aiModelConfigService.updateModel(id, request);
        return Results.success();
    }

    @DeleteMapping("/models/{id}")
    public Result<Void> deleteModel(@PathVariable("id") String id) {
        aiModelConfigService.deleteModel(id);
        return Results.success();
    }

    // ==================== 默认模型 & 优先级 ====================

    @PatchMapping("/models/{id}/set-default")
    public Result<Void> setDefaultModel(@PathVariable("id") String id) {
        aiModelConfigService.setDefaultModel(id);
        return Results.success();
    }

    @PatchMapping("/models/priorities")
    public Result<Void> updatePriorities(@Valid @RequestBody List<ModelPriorityItem> items) {
        aiModelConfigService.updatePriorities(items);
        return Results.success();
    }
}
