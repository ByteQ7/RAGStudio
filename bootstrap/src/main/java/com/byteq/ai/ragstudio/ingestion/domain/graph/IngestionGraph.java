package com.byteq.ai.ragstudio.ingestion.domain.graph;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 摄入流水线图（画布编辑态）
 * <p>
 * 图是编辑态的事实源，运行态由 {@link IngestionGraphCompiler} 编译为
 * {@code NodeConfig} 列表（含分支），存储于 {@code t_ingestion_pipeline.graph_json}。
 * <p>
 * 与工作流图的区别：流水线是确定性执行，分支为<b>排他分支</b>——
 * 按连线条件求值，首个命中者作为后继；无条件连线作为兜底（else）。
 */
public record IngestionGraph(Integer version, List<Node> nodes, List<Edge> edges) {

    public static final int CURRENT_VERSION = 1;

    /** 节点类型 */
    public static final String KIND_START = "start";
    /** 处理器节点：承载 ingestion 节点类型（fetcher/parser/chunker/...）与配置 */
    public static final String KIND_PROCESSOR = "processor";
    /** 条件网关：仅做视觉分组，分支条件挂在出边上 */
    public static final String KIND_CONDITION = "condition";
    public static final String KIND_END = "end";

    /**
     * 节点
     *
     * @param id   节点 ID
     * @param kind 节点类型
     * @param x    画布 X 坐标
     * @param y    画布 Y 坐标
     * @param data 节点数据
     */
    public record Node(String id, String kind, Double x, Double y, NodeData data) {
    }

    /**
     * 节点数据
     *
     * @param nodeType  处理器节点类型（kind=processor 必填）：fetcher/parser/chunker/enhancer/enricher/indexer/graph_extractor
     * @param settings  节点配置（kind=processor，可空）
     * @param condition 节点级门禁条件（kind=processor，可空；不满足则跳过该节点但仍继续）
     * @param note      展示备注（kind=condition，可空）
     */
    public record NodeData(String nodeType, JsonNode settings, JsonNode condition, String note) {
    }

    /**
     * 连线
     *
     * @param id        连线 ID
     * @param source    起点节点 ID
     * @param target    终点节点 ID
     * @param label     分支标签（展示用，如"是"/"否"）
     * @param condition 分支条件（可空 = 无条件/兜底分支）；格式见 ConditionEvaluator
     */
    public record Edge(String id, String source, String target, String label, JsonNode condition) {
    }

    /** 是否为空图 */
    @JsonIgnore
    public boolean isEmpty() {
        return nodes == null || nodes.isEmpty();
    }

    @JsonIgnore
    public int nodeCount() {
        return nodes != null ? nodes.size() : 0;
    }
}
