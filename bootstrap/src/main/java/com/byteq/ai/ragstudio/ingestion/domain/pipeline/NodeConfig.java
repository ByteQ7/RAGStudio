package com.byteq.ai.ragstudio.ingestion.domain.pipeline;

import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.engine.ConditionEvaluator;
import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流水线节点配置实体类
 * <p>
 * 定义摄入流水线中单个节点的配置信息，包括节点标识、类型、参数设置以及执行条件等。
 * 节点之间通过 {@code nextNodeId} 形成链式执行关系，由
 * {@link IngestionEngine} 编排执行。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeConfig {

    /**
     * 节点的唯一标识符
     * 用于在流水线中唯一标识一个节点
     */
    private String nodeId;

    /**
     * 节点类型
     * 对应 {@link IngestionNodeType} 中的枚举值，
     * 如 "fetcher"、"parser"、"chunker" 等
     */
    private String nodeType;

    /**
     * 节点的配置参数
     * 不同类型的节点有不同的配置结构，以 JSON 格式存储。
     * 例如解析器节点包含 MIME 类型规则，分块节点包含分块大小和重叠大小等。
     */
    private JsonNode settings;

    /**
     * 节点执行的条件表达式
     * 当条件满足时才会执行该节点，条件不满足时自动跳过。
     * 以 JSON 格式存储，由 {@link ConditionEvaluator} 评估。
     */
    private JsonNode condition;

    /**
     * 下一个节点 ID
     * 用于定义流水线中节点的执行顺序，指向当前节点的后继节点。
     * 如果为空，表示当前节点是流水线的末端节点。
     */
    private String nextNodeId;
}
