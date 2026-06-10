package com.byteq.ai.ragstudio.ingestion.domain.pipeline;

import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 摄入流水线定义实体类
 * <p>
 * 定义一个完整的文档摄入流水线，包含流水线的基本元信息和节点配置列表。
 * 流水线定义了文档从获取到索引的完整处理流程，由
 * {@link IngestionEngine} 负责执行。
 * </p>
 * <p>
 * 典型的流水线包含以下节点（按执行顺序）：
 * <ol>
 *   <li>FetcherNode - 文档获取</li>
 *   <li>ParserNode - 文档解析</li>
 *   <li>ChunkerNode - 文本分块</li>
 *   <li>EnricherNode - 分块富化（可选）</li>
 *   <li>EnhancerNode - 文档增强（可选）</li>
 *   <li>IndexerNode - 向量索引</li>
 * </ol>
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineDefinition {

    /**
     * 流水线的唯一标识符
     */
    private String id;

    /**
     * 流水线名称
     * 用于标识和区分不同的流水线配置
     */
    private String name;

    /**
     * 流水线描述信息
     * 说明流水线的用途、处理范围和特殊配置等
     */
    private String description;

    /**
     * 流水线中的节点配置列表
     * 每个节点配置定义了节点的类型、参数和执行顺序。
     * 节点之间通过 {@link NodeConfig#getNextNodeId()} 形成链式执行关系。
     */
    private List<NodeConfig> nodes;
}
