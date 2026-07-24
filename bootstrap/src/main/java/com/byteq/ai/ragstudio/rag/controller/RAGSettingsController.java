package com.byteq.ai.ragstudio.rag.controller;

import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.config.ModelConfigProvider;
import com.byteq.ai.ragstudio.infra.config.ModelRoutingProperties;
import com.byteq.ai.ragstudio.rag.config.MemoryProperties;
import com.byteq.ai.ragstudio.rag.config.RAGConfigProperties;
import com.byteq.ai.ragstudio.rag.config.RAGDefaultProperties;
import com.byteq.ai.ragstudio.rag.config.RAGRateLimitProperties;
import com.byteq.ai.ragstudio.rag.controller.request.SelectionUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.SystemSettingsVO;
import com.byteq.ai.ragstudio.rag.controller.vo.SystemSettingsVO.AISettings;
import com.byteq.ai.ragstudio.rag.controller.vo.SystemSettingsVO.DefaultSettings;
import com.byteq.ai.ragstudio.rag.controller.vo.SystemSettingsVO.MemorySettings;
import com.byteq.ai.ragstudio.rag.core.agent.ToolRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 设置控制器
 * <p>
 * 负责查询系统 RAG 配置、AI 模型配置、文件上传限制以及限流配置等信息。
 * 前端通过该接口获取系统运行所需的各项配置参数，用于初始化对话界面和功能开关。
 * </p>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class RAGSettingsController {

    /**
     * RAG 默认配置属性
     */
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * RAG 功能开关配置属性
     */
    private final RAGConfigProperties ragConfigProperties;

    /**
     * RAG 全局限流配置属性
     */
    private final RAGRateLimitProperties ragRateLimitProperties;

    /**
     * 对话记忆配置属性
     */
    private final MemoryProperties memoryProperties;

    /**
     * 模型路由运行时配置（用于 selection/stream 参数）
     */
    private final ModelRoutingProperties routingProperties;

    /**
     * 动态模型配置提供者（从数据库读取供应商和模型配置）
     */
    private final ModelConfigProvider modelConfigProvider;

    private final ToolRetriever toolRetriever;

    private final DefaultModelConfigService defaultModelConfigService;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    /**
     * 获取系统 RAG、AI 模型等配置信息
     * <p>
     * 聚合所有配置属性并返回给前端，包括：文件上传限制、RAG 默认配置、查询重写开关、
     * 全局限流参数、对话记忆设置以及 AI 模型提供商配置等。
     * </p>
     *
     * @return 包含所有系统配置信息的 SystemSettingsVO 视图对象
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        SystemSettingsVO response = SystemSettingsVO.builder()
                .upload(SystemSettingsVO.UploadSettings.builder()
                        .maxFileSize(maxFileSize.toBytes())
                        .maxRequestSize(maxRequestSize.toBytes())
                        .build())
                .rag(SystemSettingsVO.RagSettings.builder()
                        .defaultConfig(toDefaultSettings(ragDefaultProperties))
                        .queryRewrite(SystemSettingsVO.QueryRewriteSettings.builder()
                                .enabled(ragConfigProperties.getQueryRewriteEnabled())
                                .build())
                        .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                                .global(SystemSettingsVO.GlobalRateLimit.builder()
                                        .enabled(ragRateLimitProperties.getGlobalEnabled())
                                        .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                        .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                        .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                        .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                        .build())
                                .build())
                        .memory(toMemorySettings(memoryProperties))
                        .build())
                .ai(toAISettings())
                .build();
        return Results.success(response);
    }

    /**
     * 将 RAG 默认配置属性转换为视图对象
     *
     * @param props RAG 默认配置属性
     * @return 默认配置视图对象，包含集合名称、向量维度和度量类型
     */
    private DefaultSettings toDefaultSettings(RAGDefaultProperties props) {
        return DefaultSettings.builder()
                .collectionName(props.getCollectionName())
                .dimension(props.getDimension())
                .metricType(props.getMetricType())
                .build();
    }

    /**
     * 将对话记忆配置属性转换为视图对象
     *
     * @param props 对话记忆配置属性
     * @return 记忆配置视图对象，包含历史轮次、摘要开关等设置
     */
    private MemorySettings toMemorySettings(MemoryProperties props) {
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }

    /**
     * 将 AI 模型配置属性转换为视图对象，并对 API Key 进行脱敏处理
     * <p>
     * 供应商和模型列表从数据库读取（通过 modelConfigProvider），
     * 选择策略和流式配置从 ModelRoutingProperties 读取。
     * </p>
     *
     * @return AI 配置视图对象，包含各模型提供商及聊天、嵌入、重排序等模型组
     */
    private AISettings toAISettings() {
        DynamicModelConfig config = modelConfigProvider.getConfig();

        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (config.getProviders() != null) {
            config.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(v.getApiKey())
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(config.getChatGroup()))
                .embedding(toModelGroup(config.getEmbeddingGroup()))
                .rerank(toModelGroup(config.getRerankGroup()))
                .selection(AISettings.Selection.builder()
                          .failureThreshold(routingProperties.getSelection().getFailureThreshold())
                          .openDurationMs(routingProperties.getSelection().getOpenDurationMs())
                          .toolRoutingModel(toolRetriever.getEnabledModel())
                          .build())
                .stream(AISettings.Stream.builder()
                          .messageChunkSize(routingProperties.getStream().getMessageChunkSize())
                          .build())
                .build();
    }

    /**
     * 将 AI 模型组配置转换为视图对象
     *
     * @param group AI 模型组配置
     * @return 模型组视图对象，包含默认模型、深度思考模型和候选模型列表
     */
    private AISettings.ModelGroup toModelGroup(DynamicModelConfig.ModelGroup group) {
        if (group == null) {
            return null;
        }
        return AISettings.ModelGroup.builder()
                .defaultModel(group.getDefaultModel())
                .deepThinkingModel(null)
                .candidates(group.getCandidates() == null
                        ? null
                        : group.getCandidates().stream()
                          .map(c -> AISettings.ModelCandidate.builder()
                                    .id(c.getId())
                                    .provider(c.getProvider())
                                    .model(c.getModel())
                                    .url(c.getUrl())
                                    .dimension(c.getDimension())
                                    .dimensions(c.getDimensions())
                                    .priority(c.getPriority())
                                    .enabled(c.getEnabled())
                                    .supportsThinking(c.getSupportsThinking())
                                    .build())
                          .collect(Collectors.toList()))
                .build();
    }

    /**
     * 设置工具选择嵌入模型
     * <p>切换前探测模型可用性，成功后自动重建工具检索索引。</p>
     *
     * @param request 包含 toolRoutingModel 字段（null 或 "" 表示使用系统默认）
     * @return 操作结果
     */
    @PostMapping("/rag/settings/tool-routing-model")
    public Result<Void> setToolRoutingModel(@RequestBody SelectionUpdateRequest request) {
        String modelId = request.getToolRoutingModel();
        if (modelId != null && !modelId.isBlank()) {
            try {
                toolRetriever.probeModel(modelId);
            } catch (Exception e) {
                throw new ServiceException("工具选择嵌入模型 \"" + modelId + "\" 不可用: " + e.getMessage());
            }
            defaultModelConfigService.updateConfig("tool_selector", modelId);
        } else {
            defaultModelConfigService.deleteByKey("tool_selector");
        }
        String effective = modelId != null && !modelId.isBlank() ? modelId : null;
        toolRetriever.setModelAndRebuild(effective);
        log.info("工具选择嵌入模型已切换: {}", effective != null ? effective : "不使用（使用系统默认）");
        return Results.success(null);
    }
}
