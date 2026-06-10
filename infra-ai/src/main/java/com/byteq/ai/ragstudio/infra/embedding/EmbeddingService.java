package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.framework.exception.RemoteException;

import java.util.List;

/**
 * 嵌入服务接口
 * <p>
 * 面向业务层提供统一的向量化能力，屏蔽底层嵌入客户端的选择和路由细节。
 * 业务层通过该接口将文本转换为向量表示，无需关心使用的是哪个模型提供商。
 * </p>
 * <p>
 * <b>设计思路：</b>
 * <ul>
 *   <li>与 {@link EmbeddingClient} 分离：该接口面向业务调用方，而 EmbeddingClient 面向具体模型提供商</li>
 *   <li>提供按默认路由和指定模型两种调用方式，满足灵活性与便捷性的平衡</li>
 *   <li>支持单文本和批量文本两种嵌入模式，适应不同场景的吞吐需求</li>
 * </ul>
 * </p>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>RAG 流程中用户查询的向量化</li>
 *   <li>文档索引阶段的批量向量构建</li>
 *   <li>语义相似度计算前的向量准备</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see RoutingEmbeddingService
 * @see EmbeddingClient
 */
public interface EmbeddingService {

    /**
     * 将文本转换为嵌入向量（使用默认模型路由）
     * <p>
     * 根据系统配置的模型选择策略自动选择一个可用的嵌入模型进行处理。
     * 当默认模型不可用时，会按照路由规则自动降级到下一个候选模型。
     * </p>
     *
     * @param text 待嵌入的文本内容，不能为空
     * @return 文本的向量表示，浮点数列表
     * @throws RemoteException 当所有候选模型均不可用或调用失败时抛出
     */
    List<Float> embed(String text);

    /**
     * 将文本转换为嵌入向量（指定模型）
     * <p>
     * 调用方明确指定要使用的模型 ID，适用于对模型有特定要求的场景。
     * 如果指定的模型不可用或不存在，将直接抛出异常，不会进行降级切换。
     * </p>
     *
     * @param text    待嵌入的文本内容，不能为空
     * @param modelId 指定的模型 ID，必须与配置中的模型标识一致
     * @return 文本的向量表示，浮点数列表
     * @throws RemoteException 当指定模型不可用或调用失败时抛出
     */
    List<Float> embed(String text, String modelId);

    /**
     * 批量将多个文本转换为嵌入向量（使用默认模型路由）
     * <p>
     * 一次性处理多个文本的向量化转换，优先利用模型的批处理能力提高效率。
     * 返回结果与输入文本列表的顺序保持一致。
     * </p>
     *
     * @param texts 待嵌入的文本列表，不能为空
     * @return 文本向量列表，每个输入文本对应一个浮点数列表向量，顺序与输入一致
     * @throws RemoteException 当所有候选模型均不可用或调用失败时抛出
     */
    List<List<Float>> embedBatch(List<String> texts);

    /**
     * 批量将多个文本转换为嵌入向量（指定模型）
     * <p>
     * 使用指定的模型进行批量向量化转换。当需要对特定模型进行效果对比或
     * 业务上强制要求使用某个模型时使用此方法。
     * </p>
     *
     * @param texts   待嵌入的文本列表，不能为空
     * @param modelId 指定的模型 ID，必须与配置中的模型标识一致
     * @return 文本向量列表，每个输入文本对应一个浮点数列表向量，顺序与输入一致
     * @throws RemoteException 当指定模型不可用或调用失败时抛出
     */
    List<List<Float>> embedBatch(List<String> texts, String modelId);
}

