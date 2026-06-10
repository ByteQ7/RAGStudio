package com.byteq.ai.ragstudio.infra.rerank;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 无操作重排序客户端实现（降级用）
 * <p>
 * 当系统中没有配置可用的重排序模型时，以此实现作为保底方案。
 * 该实现不调用任何外部服务，仅对候选文档按原始顺序截取前 topN 条返回。
 * </p>
 * <p>
 * <b>设计意图：</b>
 * 避免因缺少重排序模型导致 RAG 流程中断。当重排序组件不可用时，
 * 直接使用向量检索的原始排序结果，虽然相关性可能不如重排序后的结果，
 * 但保证了系统的基本可用性。
 * </p>
 *
 * @author byteq
 * @see RerankClient
 * @see ModelProvider#NOOP
 */
@Service
public class NoopRerankClient implements RerankClient {

    @Override
    public String provider() {
        return ModelProvider.NOOP.getId();
    }

    /**
     * 执行无操作重排序
     * <p>
     * 不调用任何远程服务，直接截取候选列表的前 topN 条返回。
     * 如果候选列表为空或不需要截取，则直接返回原始列表。
     * </p>
     *
     * @param query      用户查询文本（此实现中未使用，仅用于满足接口契约）
     * @param candidates 待排序的候选文档片段列表
     * @param topN       返回前 N 个结果；若 topN 小于等于 0 或大于等于候选数量，则返回全部候选
     * @param target     目标模型配置（此实现中未使用，仅用于满足接口契约）
     * @return 截取后的文档片段列表，保持原始顺序不变
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        // 候选列表为空时直接返回空列表
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 无需截取时直接返回原始列表，避免创建新的集合对象
        if (topN <= 0 || candidates.size() <= topN) {
            return candidates;
        }
        // 按原始顺序截取前 topN 条
        return candidates.stream()
                .limit(topN)
                .collect(Collectors.toList());
    }
}
