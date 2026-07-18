package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

import java.util.List;

/**
 * 文本嵌入客户端接口
 * <p>
 * 定义将文本转换为向量表示（Embedding）的标准化契约，是接入各类 AI 模型提供商的基础抽象层。
 * 实现类负责与具体的模型服务（如 SiliconFlow、百炼 等）通信，
 * 将文本转换为固定维度的浮点数向量，用于语义检索、相似度计算等 RAG 场景。
 * </p>
 * <p>
 * <b>设计思路：</b>
 * <ul>
 *   <li>面向接口编程，屏蔽不同模型提供商在协议、认证、参数上的差异</li>
 *   <li>支持单文本嵌入与批量文本嵌入两种操作模式</li>
 *   <li>与 {@link ModelTarget} 配合使用，支持运行时动态选择模型和路由策略</li>
 * </ul>
 * </p>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>文档切片后批量构建向量索引（Indexing）</li>
 *   <li>用户查询问题向量化用于语义检索（Retrieval）</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see com.byteq.ai.ragstudio.infra.langchain4j.LangChain4jModelFactory
 * @see ModelTarget
 */
public interface EmbeddingClient {

    /**
     * 获取当前嵌入客户端的模型提供商名称
     * <p>
     * 该名称用于在路由选择时匹配对应的模型配置，需要与配置中心定义的提供商 ID 保持一致。
     * 例如：SiliconFlow 返回 "siliconflow"，百炼返回 "bailian"。
     * </p>
     *
     * @return 提供商标识字符串，非 null
     */
    String provider();

    /**
     * 将单个文本转换为嵌入向量
     * <p>
     * 对输入文本进行向量化处理，返回一个浮点数列表表示该文本在高维语义空间中的位置。
     * 向量的维度由底层模型决定（如 4096 维、768 维等）。
     * </p>
     *
     * @param text   待嵌入的文本内容，不能为空
     * @param target 目标模型配置，包含模型名称、候选模型列表等信息
     * @return 文本的向量表示，以 {@link List}{@code <Float>} 形式返回，长度固定
     * @throws ModelClientException 当请求失败、响应异常或网络错误时抛出
     */
    List<Float> embed(String text, ModelTarget target);

    /**
     * 批量将多个文本转换为嵌入向量
     * <p>
     * 同时对多个文本执行嵌入操作，通常比循环调用 {@link #embed(String, ModelTarget)} 具有更高的效率。
     * 实现类可以利用模型的批处理能力减少网络开销，返回结果与输入文本列表的顺序保持一致。
     * </p>
     *
     * @param texts  待嵌入的文本列表，不能为空
     * @param target 目标模型配置，包含模型名称、候选模型列表等信息
     * @return 文本向量列表，每个输入文本对应一个 {@link List}{@code <Float>} 向量，顺序与输入一致
     * @throws ModelClientException 当请求失败、响应异常或网络错误时抛出
     */
    List<List<Float>> embedBatch(List<String> texts, ModelTarget target);
}
