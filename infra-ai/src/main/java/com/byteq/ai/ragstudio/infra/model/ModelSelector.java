package com.byteq.ai.ragstudio.infra.model;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.infra.config.AIModelProperties;
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
 * 负责根据配置和当前需求（如普通对话、深度思考、Embedding、Rerank等）
 * 选择合适的模型候选列表。选择过程包括：
 * <ul>
 *   <li>根据能力类型（对话/嵌入/重排序）筛选对应的模型分组</li>
 *   <li>根据是否启用深度思考模式选择首选的模型</li>
 *   <li>对候选模型按配置优先级排序，优先使用首选模型</li>
 *   <li>过滤掉已被断路器标记为不可用的模型</li>
 *   <li>按排序结果构建可用的 {@link ModelTarget} 列表供路由执行器使用</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see ModelTarget
 * @see ModelHealthStore
 * @see ModelRoutingExecutor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelSelector {

    private final AIModelProperties properties;
    // 熔断器
    private final ModelHealthStore healthStore;

    /**
     * 选择对话模型的候选列表
     *
     * @param deepThinking 是否启用深度思考模式；启用时首选 deepThinkingModel，否则使用 defaultModel
     * @return 按优先级排序的可用模型目标列表；如果无可用的候选模型，返回空列表
     */
    public List<ModelTarget> selectChatCandidates(boolean deepThinking) {
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }

        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);
        return selectCandidates(group, firstChoiceModelId, deepThinking);
    }

    /**
     * 选择嵌入模型的候选列表
     *
     * @return 按优先级排序的可用嵌入模型目标列表
     */
    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }

    /**
     * 选择重排序模型的候选列表
     *
     * @return 按优先级排序的可用重排序模型目标列表
     */
    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }

    /**
     * 解析首选模型 ID
     * <p>
     * 深度思考模式下优先使用配置的 deepThinkingModel（如果存在），
     * 否则回退到 defaultModel 作为首选。
     * </p>
     *
     * @param group        模型分组配置
     * @param deepThinking 是否启用深度思考模式
     * @return 首选模型 ID
     */
    private String resolveFirstChoiceModel(AIModelProperties.ModelGroup group, boolean deepThinking) {
        if (deepThinking) {
            // 返回首选深度思考模型
            String deepModel = group.getDeepThinkingModel();
            if (StrUtil.isNotBlank(deepModel)) {
                return deepModel;
            }
        }
        // 返回普通模型
        return group.getDefaultModel();
    }

    /**
     * 选择模型候选列表（简化版，适用于非对话能力）
     * <p>
     * 使用默认配置选择候选模型，不支持深度思考模式的区分。
     * 用于 Embedding 和 Rerank 等不需要深度思考能力支持的场景。
     * </p>
     *
     * @param group 模型分组配置
     * @return 按优先级排序的可用模型目标列表
     */
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return List.of();
        }
        return selectCandidates(group, group.getDefaultModel(), false);
    }

    /**
     * 选择模型候选列表（完整版）
     * <p>
     * 执行完整的候选选择流程：先过滤排序候选模型，再构建可用的目标列表。
     * 过滤掉不可用（被断路器断开）的模型，确保返回的模型列表对路由执行器均可用。
     * </p>
     *
     * @param group              模型分组配置
     * @param firstChoiceModelId 首选模型 ID，将排在最前面
     * @param deepThinking       是否启用深度思考模式
     * @return 按优先级排序的可用模型目标列表
     */
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group, String firstChoiceModelId, boolean deepThinking) {
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        List<AIModelProperties.ModelCandidate> orderedCandidates =
                filterAndSortCandidates(group.getCandidates(), firstChoiceModelId, deepThinking);

        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 过滤并排序候选模型列表
     * <p>
     * 执行三层过滤和排序策略：
     * <ol>
     *   <li>过滤掉被禁用的候选（enabled = false）</li>
     *   <li>深度思考模式下只保留支持深度思考的模型</li>
     *   <li>按以下优先级排序：首选模型排首位 > 按 priority 升序 > 按 modelId 字典序</li>
     * </ol>
     * </p>
     *
     * @param candidates          原始候选模型列表
     * @param firstChoiceModelId  首选模型 ID，将排在最前面
     * @param deepThinking        是否启用深度思考模式
     * @return 过滤排序后的候选模型列表
     */
    private List<AIModelProperties.ModelCandidate> filterAndSortCandidates(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId,
            boolean deepThinking)
    {
        List<AIModelProperties.ModelCandidate> enabled = candidates.stream()
                // 过滤掉显式禁用的模型（enabled = false 或 null 的保留）
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                // 深度思考模式下只保留标记了 supportsThinking = true 的模型
                .filter(c -> !deepThinking || Boolean.TRUE.equals(c.getSupportsThinking()))
                .sorted(Comparator
                        // 首选模型排在最前面
                        .comparing((AIModelProperties.ModelCandidate c) ->
                                !Objects.equals(resolveId(c), firstChoiceModelId))
                        // 同优先级下按 priority 升序排列
                        .thenComparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        // 最终的稳定排序依据：按模型 ID 字典序
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());

        if (deepThinking && enabled.isEmpty()) {
            log.warn("深度思考模式没有可用候选模型");
        }

        return enabled;
    }

    /**
     * 构建可用的模型目标列表
     * <p>
     * 将候选模型配置转换为 {@link ModelTarget} 对象，同时过滤掉：
     * <ul>
     *   <li>健康状态不可用的模型（被断路器断开）</li>
     *   <li>缺少提供商配置的模型（Noop 提供商除外）</li>
     * </ul>
     * </p>
     *
     * @param candidates 候选模型配置列表
     * @return 可用的模型目标列表
     */
    private List<ModelTarget> buildAvailableTargets(List<AIModelProperties.ModelCandidate> candidates) {
        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        return candidates.stream()
                .map(candidate -> buildModelTarget(candidate, providers))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 构建单个模型目标
     * <p>
     * 如果模型已被断路器标记为不可用，或提供商配置缺失（非 Noop 提供商），
     * 返回 null 以将其从候选列表中排除。
     * </p>
     *
     * @param candidate 模型候选配置
     * @param providers 提供商配置映射
     * @return 模型目标对象，如果模型不可用则返回 null
     */
    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate, Map<String, AIModelProperties.ProviderConfig> providers) {
        String modelId = resolveId(candidate);

        // 过滤掉已被断路器断开的模型，避免向不可用的模型发起请求
        if (healthStore.isUnavailable(modelId)) {
            return null;
        }

        // 验证提供商配置是否存在（Noop 提供商不需要额外配置）
        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}", candidate.getProvider(), modelId);
            return null;
        }

        return new ModelTarget(modelId, candidate, provider);
    }

    /**
     * 解析模型的唯一标识符
     * <p>
     * 优先使用配置中显式指定的 ID；如果未指定，则使用 "provider::model" 格式自动生成。
     * 这种自动生成策略简化了配置，同时保证了模型标识的唯一性。
     * </p>
     *
     * @param candidate 模型候选配置
     * @return 模型唯一标识符
     */
    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}
