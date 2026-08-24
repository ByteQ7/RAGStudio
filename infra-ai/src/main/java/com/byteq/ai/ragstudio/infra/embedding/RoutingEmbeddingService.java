package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelHealthStore;
import com.byteq.ai.ragstudio.infra.model.ModelRoutingExecutor;
import com.byteq.ai.ragstudio.infra.model.ModelSelector;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式嵌入服务实现类
 * <p>
 * 通过模型路由机制动态选择合适的嵌入客户端，并支持失败降级策略。
 * 作为 {@link EmbeddingService} 的主要实现，使用 {@link ModelRoutingExecutor}
 * 在多个嵌入模型候选者之间进行调度，当一个模型调用失败时自动切换到下一个候选。
 * </p>
 * <p>
 * <b>工作流程：</b>
 * <ol>
 *   <li>通过 {@link ModelSelector#selectEmbeddingCandidates()} 获取候选模型列表</li>
 *   <li>使用 {@link ModelRoutingExecutor#executeWithFallback} 按优先级依次尝试</li>
 *   <li>根据目标模型的提供商名称匹配对应的 {@link EmbeddingClient} 实现</li>
 *   <li>如果某个模型调用失败，自动降级到下一个候选模型</li>
 * </ol>
 * </p>
 *
 * @author byteq
 * @see EmbeddingService
 * @see ModelRoutingExecutor
 * @see ModelSelector
 */
@Slf4j
@Service
@Primary
public class RoutingEmbeddingService implements EmbeddingService {

    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final ModelHealthStore healthStore;
    private final EmbeddingCache embeddingCache;
    private final Map<String, EmbeddingClient> clientsByProvider;

    public RoutingEmbeddingService(
            ModelSelector selector,
            ModelRoutingExecutor executor,
            ModelHealthStore healthStore,
            EmbeddingCache embeddingCache,
            List<EmbeddingClient> clients) {
        this.selector = selector;
        this.executor = executor;
        this.healthStore = healthStore;
        this.embeddingCache = embeddingCache;
        // 将客户端列表转换为 provider -> client 的映射，便于路由时快速查找
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(
                        EmbeddingClient::provider,
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("重复的 EmbeddingClient provider '{}', 使用 {}", existing.provider(), replacement.getClass().getSimpleName());
                            return replacement;
                        }));
    }

    /**
     * 将文本转换为嵌入向量（使用默认模型路由）
     * <p>
     * 通过模型路由执行器在候选嵌入模型中依次尝试，直到有一个模型成功返回结果。
     * </p>
     *
     * @param text 待嵌入的文本内容
     * @return 文本的向量表示
     */
    @Override
    @RagTraceNode(name = "embedding", type = "EMBEDDING")
    public List<Float> embed(String text) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> embeddingCache.compute(
                        target.id(), target.candidate().getDimension(), text,
                        () -> client.embed(text, target))
        );
    }

    /**
     * 将文本转换为嵌入向量（指定模型）
     * <p>
     * 使用指定的模型 ID 执行向量化。注意：当显式指定模型时，不执行降级切换，
     * 如果该模型不可用将直接抛出异常。
     * </p>
     *
     * @param text    待嵌入的文本内容
     * @param modelId 指定的模型 ID
     * @return 文本的向量表示
     */
    @Override
    @RagTraceNode(name = "embedding", type = "EMBEDDING")
    public List<Float> embed(String text, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> embeddingCache.compute(
                        target.id(), target.candidate().getDimension(), text,
                        () -> client.embed(text, target))
        );
    }

    @Override
    public List<Float> embed(String text, String modelId, Integer dimension) {
        if (dimension == null || dimension <= 0) {
            return embed(text, modelId);
        }
        List<List<Float>> batch = embedBatch(List.of(text), modelId, dimension);
        return batch.isEmpty() ? List.of() : batch.get(0);
    }

    /**
     * 绕过缓存与熔断器的直接调用（仅用于模型可用性探测）
     * <p>
     * 管理员手动检测 / 知识库创建探测必须真实触达供应商：
     * 不读取缓存（避免探测失真），且不受熔断器拦截（否则冷却期内检测
     * 只会得到 "unknown"，无法暴露真实错误）。检测成功后主动恢复
     * 熔断状态，使生产调用立即可用。
     * </p>
     */
    @Override
    public List<Float> embedDirect(String text, String modelId) {
        ModelTarget target = resolveTarget(modelId);
        EmbeddingClient client = resolveClient(target);
        if (client == null) {
            throw new RemoteException("Embedding provider client missing: " + target.candidate().getProvider());
        }
        try {
            List<Float> vector = client.embed(text, target);
            healthStore.markSuccess(target.id());
            return vector;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new RemoteException("Embedding 模型调用失败: " + msg, e, BaseErrorCode.REMOTE_ERROR);
        }
    }

    /**
     * 批量将多个文本转换为嵌入向量（使用默认模型路由）
     * <p>
     * 通过模型路由执行器在候选嵌入模型中依次尝试，利用模型的批处理能力提高效率。
     * </p>
     *
     * @param texts 待嵌入的文本列表
     * @return 文本向量列表，顺序与输入一致
     */
    @Override
    @RagTraceNode(name = "embedding-batch", type = "EMBEDDING")
    public List<List<Float>> embedBatch(List<String> texts) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> embeddingCache.computeBatch(
                        target.id(), target.candidate().getDimension(), texts,
                        missing -> client.embedBatch(missing, target))
        );
    }

    /**
     * 批量将多个文本转换为嵌入向量（指定模型）
     *
     * @param texts   待嵌入的文本列表
     * @param modelId 指定的模型 ID
     * @return 文本向量列表，顺序与输入一致
     */
    @Override
    @RagTraceNode(name = "embedding-batch", type = "EMBEDDING")
    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> embeddingCache.computeBatch(
                        target.id(), target.candidate().getDimension(), texts,
                        missing -> client.embedBatch(missing, target))
        );
    }

    @Override
    @RagTraceNode(name = "embedding-batch", type = "EMBEDDING")
    public List<List<Float>> embedBatch(List<String> texts, String modelId, Integer dimension) {
        if (dimension == null || dimension <= 0) {
            return embedBatch(texts, modelId);
        }
        ModelTarget original = resolveTarget(modelId);
        DynamicModelConfig.ModelEntry entry = DynamicModelConfig.ModelEntry.builder()
                .id(original.candidate().getId())
                .provider(original.candidate().getProvider())
                .model(original.candidate().getModel())
                .url(original.candidate().getUrl())
                .dimension(dimension)
                .dimensions(original.candidate().getDimensions())
                .priority(original.candidate().getPriority())
                .enabled(original.candidate().getEnabled())
                .supportsThinking(original.candidate().getSupportsThinking())
                .supportsMultimodal(original.candidate().getSupportsMultimodal())
                .isDefault(original.candidate().getIsDefault())
                .capability(original.candidate().getCapability())
                .protocol(original.candidate().getProtocol())
                .build();
        ModelTarget target = new ModelTarget(original.id(), entry, original.provider());
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(target),
                this::resolveClient,
                (client, t) -> embeddingCache.computeBatch(
                        t.id(), t.candidate().getDimension(), texts,
                        missing -> client.embedBatch(missing, t))
        );
    }

    /**
     * 根据模型目标解析对应的嵌入客户端
     * <p>
     * 从目标对象的候选模型中提取提供商名称，然后从客户端映射中查找对应的实现。
     * 如果找不到匹配的客户端（如未注册的提供商），返回 null。
     * </p>
     *
     * @param target 模型目标配置
     * @return 对应的嵌入客户端实例，如果未找到则返回 null
     */
    private EmbeddingClient resolveClient(ModelTarget target) {
        return clientsByProvider.get(target.candidate().getProvider());
    }

    /**
     * 根据模型 ID 解析模型目标
     * <p>
     * 在嵌入模型的候选列表中查找指定模型 ID 的目标配置。
     * 用于指定模型调用的场景，将外部传入的模型 ID 转换为内部使用的 {@link ModelTarget}。
     * </p>
     *
     * @param modelId 模型 ID
     * @return 对应的模型目标
     * @throws RemoteException 如果模型 ID 为空或未在候选列表中找到
     */
    private ModelTarget resolveTarget(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new RemoteException("Embedding 模型ID不能为空");
        }
        return selector.selectEmbeddingCandidates().stream()
                .filter(target -> modelId.equals(target.id()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Embedding 模型不可用: " + modelId));
    }
}
