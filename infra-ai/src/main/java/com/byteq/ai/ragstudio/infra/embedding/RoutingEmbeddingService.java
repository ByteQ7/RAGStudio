package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.infra.model.ModelRoutingExecutor;
import com.byteq.ai.ragstudio.infra.model.ModelSelector;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
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
@Service
@Primary
public class RoutingEmbeddingService implements EmbeddingService {

    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, EmbeddingClient> clientsByProvider;

    public RoutingEmbeddingService(
            ModelSelector selector,
            ModelRoutingExecutor executor,
            List<EmbeddingClient> clients) {
        this.selector = selector;
        this.executor = executor;
        // 将客户端列表转换为 provider -> client 的映射，便于路由时快速查找
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
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
    public List<Float> embed(String text) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> client.embed(text, target)
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
    public List<Float> embed(String text, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.embed(text, target)
        );
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
    public List<List<Float>> embedBatch(List<String> texts) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                selector.selectEmbeddingCandidates(),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
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
    public List<List<Float>> embedBatch(List<String> texts, String modelId) {
        return executor.executeWithFallback(
                ModelCapability.EMBEDDING,
                List.of(resolveTarget(modelId)),
                this::resolveClient,
                (client, target) -> client.embedBatch(texts, target)
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
