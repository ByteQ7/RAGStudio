package com.byteq.ai.ragstudio.infra.rerank;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

import java.util.List;

/**
 * 重排序客户端接口
 * <p>
 * 定义对检索结果进行语义重排序（Rerank）的标准契约，是 RAG 系统中精排阶段的关键抽象。
 * 实现类对接不同的重排序服务提供商（如百炼、Jina 等），对向量检索返回的候选文档
 * 进行更精确的相关性评分和重新排序。
 * </p>
 * <p>
 * <b>设计思路：</b>
 * <ul>
 *   <li>与 {@link RerankService} 分离：该接口面向具体的模型提供商，RerankService 面向业务层</li>
 *   <li>重排序是 RAG 流程中"检索-精排-生成"三段式的中间环节，用于提升送入大模型的文档质量</li>
 *   <li>通过 {@link ModelTarget} 支持运行时动态选择不同的重排序模型和路由策略</li>
 * </ul>
 * </p>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>向量检索后对 topK 候选文档进行重排序，提高召回结果的相关性密度</li>
 *   <li>多路召回结果合并后的统一精排</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see BaiLianRerankClient
 * @see NoopRerankClient
 * @see RerankService
 */
public interface RerankClient {

    /**
     * 获取当前重排序客户端的模型提供商名称
     * <p>
     * 该名称用于在路由选择时匹配对应的模型配置，需要与配置中心定义的提供商 ID 保持一致。
     * 例如：百炼返回 "bailian"，Noop 返回 "noop"。
     * </p>
     *
     * @return 提供商标识字符串，非 null
     */
    String provider();

    /**
     * 对检索到的候选文档片段执行语义重排序
     * <p>
     * 以用户查询文本为核心，对候选文档列表进行语义级别的相关性评分和重新排序。
     * 重排序模型通常比向量检索的相似度计算更精确，能够捕捉更深层的语义匹配关系。
     * </p>
     *
     * @param query      用户查询文本，作为相关性评分的基准
     * @param candidates 待排序的候选文档片段列表，通常来自向量检索的返回结果
     * @param topN       返回前 N 个最相关的结果；如果 topN 大于等于候选数量，返回全部候选
     * @param target     目标模型配置，包含模型名称、候选模型列表等信息
     * @return 重排序后的文档片段列表，按相关性从高到低排列，最多包含 topN 条
     * @throws ModelClientException 当请求失败、响应异常或网络错误时抛出
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target);
}
