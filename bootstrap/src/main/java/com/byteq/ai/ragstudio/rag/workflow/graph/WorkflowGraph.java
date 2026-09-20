package com.byteq.ai.ragstudio.rag.workflow.graph;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 工作流图（画布编辑态）
 * <p>
 * 图是编辑态的事实源，线性 {@code steps} 是运行态的编译产物（见 {@link WorkflowGraphCompiler}）。
 * 存储于 {@code t_workflow.graph_json}，Agent 执行链路不感知本结构。
 *
 * @param version 图模型版本（当前 {@link #CURRENT_VERSION}）
 * @param nodes   节点列表
 * @param edges   连线列表
 */
public record WorkflowGraph(Integer version, List<Node> nodes, List<Edge> edges) {

    public static final int CURRENT_VERSION = 1;

    /** 节点类型 */
    public static final String KIND_START = "start";
    public static final String KIND_STEP = "step";
    public static final String KIND_CONDITION = "condition";
    public static final String KIND_END = "end";

    /**
     * 节点
     *
     * @param id   节点 ID（画布内唯一）
     * @param kind 节点类型：start / step / condition / end
     * @param x    画布 X 坐标（可空，加载时自动布局）
     * @param y    画布 Y 坐标（可空，加载时自动布局）
     * @param data 节点数据
     */
    public record Node(String id, String kind, Double x, Double y, NodeData data) {
    }

    /**
     * 节点数据（按 kind 取用不同字段）
     *
     * @param action     步骤动作（kind=step，必填）
     * @param tool       建议工具（kind=step，可空）
     * @param when       步骤执行条件（kind=step，可空；由旧线性数据迁移而来，与路径条件合并）
     * @param expression 条件表达式（kind=condition，必填）
     */
    public record NodeData(String action, String tool, String when, String expression) {
    }

    /**
     * 连线
     *
     * @param id     连线 ID
     * @param source 起点节点 ID
     * @param target 终点节点 ID
     * @param label  分支标签（condition 出边必填，如"是"/"否"）
     */
    public record Edge(String id, String source, String target, String label) {
    }

    /** 是否为空图（无节点）；派生方法，不参与 JSON 序列化 */
    @JsonIgnore
    public boolean isEmpty() {
        return nodes == null || nodes.isEmpty();
    }

    /** 节点数（防御 null） */
    public int nodeCount() {
        return nodes != null ? nodes.size() : 0;
    }
}
