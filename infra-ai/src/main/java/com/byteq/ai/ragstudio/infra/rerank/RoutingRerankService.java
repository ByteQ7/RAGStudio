package com.byteq.ai.ragstudio.infra.rerank;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.infra.model.ModelRoutingExecutor;
import com.byteq.ai.ragstudio.infra.model.ModelSelector;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式重排序服务实现类
 * <p>
 * 通过模型路由机制动态选择合适的重排序客户端，并支持失败降级策略。
 * 作为 {@link RerankService} 的主要实现，使用 {@link ModelRoutingExecutor}
 * 在多个重排序模型候选者之间进行调度，当一个模型调用失败时自动切换到下一个候选。
 * </p>
 * <p>
 * <b>工作流程：</b>
 * <ol>
 *   <li>通过 {@link ModelSelector#selectRerankCandidates()} 获取候选模型列表</li>
 *   <li>使用 {@link ModelRoutingExecutor#executeWithFallback} 按优先级依次尝试</li>
 *   <li>根据目标模型的提供商名称匹配对应的 {@link RerankClient} 实现</li>
 *   <li>如果某个模型调用失败，自动降级到下一个候选模型</li>
 * </ol>
 * </p>
 *
 * @author byteq
 * @see RerankService
 * @see ModelRoutingExecutor
 * @see ModelSelector
 */
@Service
@Primary
public class RoutingRerankService implements RerankService {

    private final ModelSelector selector;
    private final ModelRoutingExecutor executor;
    private final Map<String, RerankClient> clientsByProvider;

    public RoutingRerankService(ModelSelector selector, ModelRoutingExecutor executor, List<RerankClient> clients) {
        this.selector = selector;
        this.executor = executor;
        // 将客户端列表转换为 provider -> client 的映射，便于路由时快速查找
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(RerankClient::provider, Function.identity()));
    }

    /**
     * 执行路由式重排序
     * <p>
     * 通过模型路由执行器在候选重排序模型中依次尝试，直到有一个模型成功返回结果。
     * 如果所有候选模型均失败，抛出 {@link RemoteException}。
     * </p>
     *
     * @param query      用户查询文本
     * @param candidates 待排序的候选文档列表
     * @param topN       返回前 N 个最相关的结果
     * @return 重排序后的文档片段列表
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        return executor.executeWithFallback(
                ModelCapability.RERANK,
                selector.selectRerankCandidates(),
                // 根据目标模型的提供商名称查找对应的客户端实现
                target -> clientsByProvider.get(target.candidate().getProvider()),
                // 使用查到的客户端执行重排序调用
                (client, target) -> client.rerank(query, candidates, topN, target)
        );
    }
}
