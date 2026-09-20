package com.byteq.ai.ragstudio.rag.workflow.graph;

import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流图工厂：线性 steps → 线性图（旧数据兼容）
 * <p>
 * 存量工作流只有 {@code steps} 没有 {@code graph_json}，读取时自动生成一张线性图，
 * 用户可在画布上继续加工分支；首次保存画布后落库。
 * <p>
 * 旧步骤的 {@code when} 保留在 step 节点的 data 中（画布上显示为条件徽标），
 * 不强行转换为 condition 节点——避免"仅当条件满足时执行"的语义在图上产生歧义。
 */
public final class WorkflowGraphFactory {

    /** 画布默认布局间距 */
    private static final double ORIGIN_X = 80;
    private static final double ORIGIN_Y = 160;
    private static final double X_GAP = 240;

    private static final String START_ID = "start";
    private static final String END_ID = "end";

    private WorkflowGraphFactory() {
    }

    /** 由线性步骤生成线性图（steps 为空时返回仅含 start/end 的骨架图） */
    public static WorkflowGraph fromSteps(List<WorkflowDefinition.WorkflowStep> steps) {
        List<WorkflowGraph.Node> nodes = new ArrayList<>();
        List<WorkflowGraph.Edge> edges = new ArrayList<>();

        nodes.add(new WorkflowGraph.Node(START_ID, WorkflowGraph.KIND_START,
                ORIGIN_X, ORIGIN_Y, new WorkflowGraph.NodeData(null, null, null, null)));

        String previous = START_ID;
        int index = 0;
        if (steps != null) {
            for (WorkflowDefinition.WorkflowStep step : steps) {
                if (step == null) {
                    continue;
                }
                index++;
                String id = "step-" + index;
                nodes.add(new WorkflowGraph.Node(id, WorkflowGraph.KIND_STEP,
                        ORIGIN_X + index * X_GAP, ORIGIN_Y,
                        new WorkflowGraph.NodeData(step.action(), step.tool(), step.when(), null)));
                edges.add(new WorkflowGraph.Edge("e-" + index, previous, id, null));
                previous = id;
            }
        }

        nodes.add(new WorkflowGraph.Node(END_ID, WorkflowGraph.KIND_END,
                ORIGIN_X + (index + 1) * X_GAP, ORIGIN_Y,
                new WorkflowGraph.NodeData(null, null, null, null)));
        edges.add(new WorkflowGraph.Edge("e-end", previous, END_ID, null));

        return new WorkflowGraph(WorkflowGraph.CURRENT_VERSION, nodes, edges);
    }

    /** 图是否需要自动布局（存在缺失坐标的节点） */
    public static boolean needsAutoLayout(WorkflowGraph graph) {
        if (graph == null || graph.nodes() == null) {
            return true;
        }
        return graph.nodes().stream().anyMatch(n -> n.x() == null || n.y() == null);
    }
}
