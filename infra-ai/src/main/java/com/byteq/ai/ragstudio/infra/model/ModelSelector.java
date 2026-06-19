package com.byteq.ai.ragstudio.infra.model;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.config.ModelConfigProvider;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 模型选择器
 * <p>
 * 根据 {@link ModelConfigProvider} 提供的动态配置和健康状态，
 * 筛选并排序可用的模型候选列表，供 {@link ModelRoutingExecutor} 进行路由调度。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final ModelConfigProvider configProvider;
    private final ModelHealthStore healthStore;

    /**
     * 选择 Chat 模型候选列表
     *
     * @param deepThinking 是否要求深度思考能力
     * @return 按优先级排序的可用模型列表
     */
    public List<ModelTarget> selectChatCandidates(boolean deepThinking) {
        DynamicModelConfig config = configProvider.getConfig();
        DynamicModelConfig.ModelGroup group = config.getChatGroup();
        if (group == null || group.getCandidates().isEmpty()) {
            return List.of();
        }
        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);
        return selectCandidates(group, firstChoiceModelId, deepThinking, config.getProviders());
    }

    /**
     * 选择 Embedding 模型候选列表
     */
    public List<ModelTarget> selectEmbeddingCandidates() {
        DynamicModelConfig config = configProvider.getConfig();
        DynamicModelConfig.ModelGroup group = config.getEmbeddingGroup();
        if (group == null || group.getCandidates().isEmpty()) {
            return List.of();
        }
        return selectCandidates(group, group.getDefaultModel(), false, config.getProviders());
    }

    /**
     * 选择 Rerank 模型候选列表
     */
    public List<ModelTarget> selectRerankCandidates() {
        DynamicModelConfig config = configProvider.getConfig();
        DynamicModelConfig.ModelGroup group = config.getRerankGroup();
        if (group == null || group.getCandidates().isEmpty()) {
            return List.of();
        }
        return selectCandidates(group, group.getDefaultModel(), false, config.getProviders());
    }

    // 确定首选模型 ID，深度思考模式下直接返回默认模型（后续由 filterAndSortCandidates 按 supportsThinking 过滤）
    private String resolveFirstChoiceModel(DynamicModelConfig.ModelGroup group, boolean deepThinking) {
        // 深度思考模式暂无独立的"deepThinkingModel"配置，
        // 直接返回 defaultModel，filterAndSortCandidates 会按 supportsThinking 过滤
        return group.getDefaultModel();
    }

    // 从模型分组中筛选并排序候选模型，构建可用的 ModelTarget 列表
    private List<ModelTarget> selectCandidates(
            DynamicModelConfig.ModelGroup group,
            String firstChoiceModelId,
            boolean deepThinking,
            Map<String, DynamicModelConfig.ProviderEntry> providers) {

        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        List<DynamicModelConfig.ModelEntry> orderedCandidates =
                filterAndSortCandidates(group.getCandidates(), firstChoiceModelId, deepThinking);

        return buildAvailableTargets(orderedCandidates, providers);
    }

    // 候选模型筛选与排序:
    // 1. 过滤掉已禁用的模型
    // 2. 深度思考模式下过滤不支持 thinking 的模型
    // 3. 按首选模型优先、优先级升序、ID 字典序排列
    private List<DynamicModelConfig.ModelEntry> filterAndSortCandidates(
            List<DynamicModelConfig.ModelEntry> candidates,
            String firstChoiceModelId,
            boolean deepThinking) {

        List<DynamicModelConfig.ModelEntry> enabled = candidates.stream()
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                .filter(c -> !deepThinking || Boolean.TRUE.equals(c.getSupportsThinking()))
                .sorted(Comparator
                        .comparing((DynamicModelConfig.ModelEntry c) ->
                                !Objects.equals(resolveId(c), firstChoiceModelId))
                        .thenComparing(DynamicModelConfig.ModelEntry::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(DynamicModelConfig.ModelEntry::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());

        if (deepThinking && enabled.isEmpty()) {
            log.warn("深度思考模式没有可用候选模型");
        }
        return enabled;
    }

    // 将筛选后的候选模型列表转换为 ModelTarget，过滤掉不可用的条目
    private List<ModelTarget> buildAvailableTargets(
            List<DynamicModelConfig.ModelEntry> candidates,
            Map<String, DynamicModelConfig.ProviderEntry> providers) {

        return candidates.stream()
                .map(candidate -> buildModelTarget(candidate, providers))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // 为单个候选模型构建 ModelTarget，检查健康状态和 Provider 配置是否可用
    private ModelTarget buildModelTarget(
            DynamicModelConfig.ModelEntry candidate,
            Map<String, DynamicModelConfig.ProviderEntry> providers) {

        String modelId = resolveId(candidate);
        if (healthStore.isUnavailable(modelId)) {
            return null;
        }

        DynamicModelConfig.ProviderEntry provider = providers.get(candidate.getProvider());
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}", candidate.getProvider(), modelId);
            return null;
        }

        return new ModelTarget(modelId, candidate, provider);
    }

    // 解析模型唯一标识：优先使用配置的 ID，否则用 "provider::model" 格式生成
    private String resolveId(DynamicModelConfig.ModelEntry candidate) {
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}
