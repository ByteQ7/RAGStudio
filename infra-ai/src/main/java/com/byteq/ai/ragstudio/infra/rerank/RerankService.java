package com.byteq.ai.ragstudio.infra.rerank;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.exception.RemoteException;

import java.util.List;

/**
 * 重排序服务接口
 * <p>
 * 面向业务层提供文档重排序（Rerank）能力，对向量检索返回的候选文档进行语义级别的精排，
 * 按与用户查询的相关度重新排序，并只返回前 topN 条结果。
 * </p>
 * <p>
 * <b>设计思路：</b>
 * <ul>
 *   <li>与 {@link RerankClient} 分离：该接口面向业务调用方，RerankClient 面向具体模型提供商</li>
 *   <li>业务层无需关心选择哪个重排序模型或如何路由，由实现类（如 {@link RoutingRerankService}）自动处理</li>
 *   <li>重排序是 RAG"检索-精排-生成"流水线中的关键环节，可显著提升最终生成质量</li>
 * </ul>
 * </p>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>RAG 流程中向量检索后的文档精排</li>
 *   <li>多路召回合并后的统一排序</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see RoutingRerankService
 * @see RerankClient
 */
public interface RerankService {

    /**
     * 对检索候选文档执行语义重排序
     * <p>
     * 以用户查询为基准，对候选文档列表进行语义相关性评分和重新排序，
     * 只保留相关性最高的前 topN 条结果。如果 topN 大于等于候选数量，则返回全部候选。
     * </p>
     *
     * @param query      用户查询文本，作为相关性评分的基准
     * @param candidates 待排序的候选文档列表，通常来自向量检索的返回结果
     * @param topN       最终希望保留的相关文档数量（将送入大模型的上下文窗口）
     * @return 重排序后的文档片段列表，按相关性从高到低排列，最多包含 topN 条
     * @throws RemoteException 当所有候选重排序模型均不可用或调用失败时抛出
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
