package com.byteq.ai.ragstudio.aimodel.controller;

import com.byteq.ai.ragstudio.aimodel.controller.vo.DefaultModelConfigVO;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 场景默认模型配置控制器
 * <p>管理各场景（对话、摘要、标题、多模态、文档图片）的默认模型。</p>
 */
@Slf4j
@RestController
@RequestMapping("/ai-model-config/defaults")
@RequiredArgsConstructor
public class DefaultModelConfigController {

    private final DefaultModelConfigService defaultModelConfigService;

    /**
     * 查询所有场景的默认模型配置
     */
    @GetMapping
    public Result<List<DefaultModelConfigVO>> listAll() {
        return Results.success(defaultModelConfigService.listAll());
    }

    /**
     * 获取指定场景的默认模型配置
     *
     * @param configKey 配置键
     */
    @GetMapping("/{configKey}")
    public Result<DefaultModelConfigVO> getConfig(@PathVariable("configKey") String configKey) {
        return Results.success(defaultModelConfigService.getConfig(configKey));
    }

    /**
     * 更新指定场景的默认模型
     *
     * @param configKey 配置键
     * @param body      请求体 { "modelId": "xxx" }
     */
    @PutMapping("/{configKey}")
    public Result<Void> updateConfig(@PathVariable("configKey") String configKey,
                                     @RequestBody Map<String, String> body) {
        String modelId = body.get("modelId");
        defaultModelConfigService.updateConfig(configKey, modelId);
        return Results.success();
    }
}
