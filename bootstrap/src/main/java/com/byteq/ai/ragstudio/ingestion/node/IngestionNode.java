package com.byteq.ai.ragstudio.ingestion.node;

import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;

/**
 * 摄入节点接口，定义了数据摄入流水线中处理节点的基本契约。
 * <p>
 * 每个节点代表流水线中的一个处理步骤，通过实现该接口来完成特定的处理逻辑。
 * 节点之间通过 {@link NodeConfig#getNextNodeId()} 形成链式调用关系，
 * 由 {@link IngestionEngine} 统一编排执行。
 * </p>
 * <p>
 * 系统内置了以下节点实现，按执行顺序排列：
 * <ol>
 *   <li>{@link FetcherNode} - 文档获取节点：从各种数据源获取原始文档字节流</li>
 *   <li>{@link ParserNode} - 文档解析节点：将原始字节解析为结构化文本</li>
 *   <li>{@link ChunkerNode} - 文本分块节点：将文本切分为较小的文本块</li>
 *   <li>{@link EnricherNode} - 分块富化节点：对每个文本块进行元数据富化</li>
 *   <li>{@link EnhancerNode} - 文档增强节点：对整个文档进行 AI 增强处理</li>
 *   <li>{@link IndexerNode} - 向量索引节点：将文本块向量化并存入向量数据库</li>
 * </ol>
 * </p>
 */
public interface IngestionNode {

    /**
     * 获取节点类型标识
     * <p>
     * 返回的字符串必须与 {@link IngestionNodeType}
     * 中定义的值对应，用于在引擎中查找对应的节点实现。
     * </p>
     *
     * @return 节点类型的字符串标识（如 "fetcher"、"parser"、"chunker" 等）
     */
    String getNodeType();

    /**
     * 执行节点的具体逻辑
     * <p>
     * 节点从 {@link IngestionContext} 中读取输入数据，执行处理后
     * 将结果写回上下文，供后续节点使用。
     * </p>
     *
     * @param context 摄入上下文，包含共享状态和流水线中传递的中间数据
     * @param config  当前节点的配置信息，包含节点参数和执行条件
     * @return 节点执行的结果，包含执行状态、消息和错误信息
     */
    NodeResult execute(IngestionContext context, NodeConfig config);
}
