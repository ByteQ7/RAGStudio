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
        return selectChatCandidates(deepThinking, false);
    }

    /**
     * 选择 Chat 模型候选列表（支持多模态过滤）
     *
     * @param deepThinking 是否要求深度思考能力
     * @param multimodal   是否要求多模态（图片识别）能力
     * @return 按优先级排序的可用模型列表
     */
    public List<ModelTarget> selectChatCandidates(boolean deepThinking, boolean multimodal) {
        DynamicModelConfig config = configProvider.getConfig();
        DynamicModelConfig.ModelGroup group = config.getChatGroup();
        if (group == null || group.getCandidates().isEmpty()) {
            return List.of();
        }
        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);
        return selectCandidates(group, firstChoiceModelId, deepThinking, multimodal, config.getProviders());
    }

    /**
     * 选择 Chat 模型候选列表（支持指定默认模型 + 多模态过滤）
     * <p>
     * 用于场景化默认模型（如对话、摘要、标题、多模态、文档图片），
     * 允许外部传入 preferredModelId 作为首选，覆盖 group 级别的默认模型。
     * </p>
     *
     * @param preferredModelId 首选模型 ID，为空时使用 group 默认
     * @param deepThinking     是否要求深度思考能力
     * @param multimodal       是否要求多模态（图片识别）能力
     * @return 按优先级排序的可用模型列表
     */
    public List<ModelTarget> selectChatCandidates(String preferredModelId, boolean deepThinking, boolean multimodal) {
        DynamicModelConfig config = configProvider.getConfig();
        DynamicModelConfig.ModelGroup group = config.getChatGroup();
        if (group == null || group.getCandidates().isEmpty()) {
            return List.of();
        }
        String firstChoiceModelId = preferredModelId != null ? preferredModelId : resolveFirstChoiceModel(group, deepThinking);
        return selectCandidates(group, firstChoiceModelId, deepThinking, multimodal, config.getProviders());
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
        return selectCandidates(group, firstChoiceModelId, deepThinking, false, providers);
    }

    // 从模型分组中筛选并排序候选模型（支持多模态过滤），构建可用的 ModelTarget 列表
    private List<ModelTarget> selectCandidates(
            DynamicModelConfig.ModelGroup group,
            String firstChoiceModelId,
            boolean deepThinking,
            boolean multimodal,
            Map<String, DynamicModelConfig.ProviderEntry> providers) {

        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        List<DynamicModelConfig.ModelEntry> orderedCandidates =
                filterAndSortCandidates(group.getCandidates(), firstChoiceModelId, deepThinking, multimodal);

        return buildAvailableTargets(orderedCandidates, providers);
    }

    // 候选模型筛选与排序:
    // 1. 过滤掉已禁用的模型
    // 2. 过滤掉供应商已禁用的模型（provider 为空或不在 providers 映射中）
    // 3. 深度思考模式下过滤不支持 thinking 的模型
    // 4. 多模态模式下过滤不支持 multimodial 的模型
    // 5. 按首选模型优先、优先级升序、ID 字典序排列
    private List<DynamicModelConfig.ModelEntry> filterAndSortCandidates(
            List<DynamicModelConfig.ModelEntry> candidates,
            String firstChoiceModelId,
            boolean deepThinking) {
        return filterAndSortCandidates(candidates, firstChoiceModelId, deepThinking, false);
    }

    // 候选模型筛选与排序（支持多模态过滤）
    private List<DynamicModelConfig.ModelEntry> filterAndSortCandidates(
            List<DynamicModelConfig.ModelEntry> candidates,
            String firstChoiceModelId,
            boolean deepThinking,
            boolean multimodal) {

        // 注意：此方法无 providers 参数，仅做模型级别过滤。
        // 供应商级别的过滤在 buildAvailableTargets/buildModelTarget 中完成。
        // 但如果 provider 信息明显为空（数据加载阶段已出错），此处提前丢弃。
        List<DynamicModelConfig.ModelEntry> enabled = candidates.stream()
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                .filter(c -> StrUtil.isNotBlank(c.getProvider()))
                .filter(c -> !deepThinking || Boolean.TRUE.equals(c.getSupportsThinking()))
                .filter(c -> !multimodal || Boolean.TRUE.equals(c.getSupportsMultimodal()))
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
        if (multimodal && enabled.isEmpty()) {
            log.warn("多模态模式没有可用候选模型");
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

        // 注意：不在此处用 isUnavailable() 过滤熔断中模型。
        // 熔断状态由 ModelRoutingExecutor 在运行时通过 allowCall() 处理，
        // 确保候选列表完整，让 executor 的 fallback 循环能遍历所有模型。
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
