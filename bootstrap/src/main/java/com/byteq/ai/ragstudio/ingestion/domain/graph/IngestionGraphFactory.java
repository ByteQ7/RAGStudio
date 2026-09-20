package com.byteq.ai.ragstudio.ingestion.domain.graph;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 摄入流水线图工厂：旧线性节点配置 → 线性图（旧数据兼容）
 * <p>
 * 存量流水线只有 {@code t_ingestion_pipeline_node} 行没有 {@code graph_json}，
 * 读取时自动生成一张图（start → n1 → n2 → … → end），用户可在画布上继续加工分支；
 * 首次保存画布后落库。若旧行已含分支（新版本写入），则生成条件网关节点表达分支。
 */
public final class IngestionGraphFactory {

    private static final double ORIGIN_X = 80;
    private static final double ORIGIN_Y = 160;
    private static final double X_GAP = 260;

    private static final String START_ID = "start";
    private static final String END_ID = "end";

    private IngestionGraphFactory() {
    }

    /** 由节点配置列表生成图（按 nextNodeId/分支 推导顺序） */
    public static IngestionGraph fromNodes(List<NodeConfig> nodes) {
        Map<String, NodeConfig> nodeById = new LinkedHashMap<>();
        if (nodes != null) {
            for (NodeConfig node : nodes) {
                if (node != null && StrUtil.isNotBlank(node.getNodeId())) {
                    nodeById.putIfAbsent(node.getNodeId(), node);
                }
            }
        }
        List<String> ordered = chainOrder(nodeById);

        List<IngestionGraph.Node> graphNodes = new ArrayList<>();
        List<IngestionGraph.Edge> graphEdges = new ArrayList<>();
        graphNodes.add(new IngestionGraph.Node(START_ID, IngestionGraph.KIND_START,
                ORIGIN_X, ORIGIN_Y, new IngestionGraph.NodeData(null, null, null, null)));

        // 处理器节点
        Map<String, String> graphIdByNodeId = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            String nodeId = ordered.get(i);
            NodeConfig config = nodeById.get(nodeId);
            // 保留原始 nodeId：任务节点日志与历史数据均以该 ID 关联
            String graphId = nodeId;
            graphIdByNodeId.put(nodeId, graphId);
            graphNodes.add(new IngestionGraph.Node(graphId, IngestionGraph.KIND_PROCESSOR,
                    ORIGIN_X + (i + 1) * X_GAP, ORIGIN_Y,
                    new IngestionGraph.NodeData(config.getNodeType(), config.getSettings(),
                            config.getCondition(), null)));
        }

        int edgeSeq = 0;
        // 起点 → 首个处理器（无处理器则直接到结束）
        String firstGraphId = ordered.isEmpty() ? END_ID : graphIdByNodeId.get(ordered.get(0));
        graphEdges.add(new IngestionGraph.Edge("e" + (++edgeSeq), START_ID, firstGraphId, null, null));

        // 各处理器的出边
        int gatewaySeq = 0;
        for (String nodeId : ordered) {
            NodeConfig config = nodeById.get(nodeId);
            String graphId = graphIdByNodeId.get(nodeId);
            boolean hasBranches = config.getBranches() != null && !config.getBranches().isEmpty();
            if (hasBranches) {
                String gatewayId = "c" + (++gatewaySeq);
                graphNodes.add(new IngestionGraph.Node(gatewayId, IngestionGraph.KIND_CONDITION,
                        ORIGIN_X + positionIndex(nodeId, ordered) * X_GAP + X_GAP / 2, ORIGIN_Y,
                        new IngestionGraph.NodeData(null, null, null, "分支")));
                graphEdges.add(new IngestionGraph.Edge("e" + (++edgeSeq), graphId, gatewayId, null, null));
                for (NodeConfig.Branch branch : config.getBranches()) {
                    if (branch == null || StrUtil.isBlank(branch.getNextNodeId())) {
                        continue;
                    }
                    String targetGraphId = graphIdByNodeId.get(branch.getNextNodeId());
                    if (targetGraphId != null || END_ID.equals(branch.getNextNodeId())) {
                        graphEdges.add(new IngestionGraph.Edge("e" + (++edgeSeq), gatewayId,
                                targetGraphId != null ? targetGraphId : END_ID, null, branch.getCondition()));
                    }
                }
                if (StrUtil.isNotBlank(config.getNextNodeId())) {
                    String fallbackTarget = END_ID.equals(config.getNextNodeId())
                            ? END_ID : graphIdByNodeId.get(config.getNextNodeId());
                    if (fallbackTarget != null) {
                        graphEdges.add(new IngestionGraph.Edge("e" + (++edgeSeq), gatewayId,
                                fallbackTarget, "兜底", null));
                    }
                }
            } else if (StrUtil.isNotBlank(config.getNextNodeId())) {
                String targetGraphId = END_ID.equals(config.getNextNodeId())
                        ? END_ID : graphIdByNodeId.get(config.getNextNodeId());
                if (targetGraphId != null) {
                    graphEdges.add(new IngestionGraph.Edge("e" + (++edgeSeq), graphId, targetGraphId, null, null));
                }
            } else {
                // 链尾 → 结束
                graphEdges.add(new IngestionGraph.Edge("e" + (++edgeSeq), graphId, END_ID, null, null));
            }
        }

        graphNodes.add(new IngestionGraph.Node(END_ID, IngestionGraph.KIND_END,
                ORIGIN_X + (ordered.size() + 2) * X_GAP, ORIGIN_Y,
                new IngestionGraph.NodeData(null, null, null, null)));

        return new IngestionGraph(IngestionGraph.CURRENT_VERSION, graphNodes, graphEdges);
    }

    private static int positionIndex(String nodeId, List<String> ordered) {
        int index = ordered.indexOf(nodeId);
        return index < 0 ? 0 : index + 1;
    }

    /**
     * 计算链顺序：从无入链的节点开始沿 nextNodeId/分支遍历，未覆盖节点按原顺序补足。
     * 仅用于旧数据生成的展示顺序，不参与执行（执行由 runtime 的 nextNodeId/branches 驱动）。
     */
    private static List<String> chainOrder(Map<String, NodeConfig> nodeById) {
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeById.values()) {
            if (StrUtil.isNotBlank(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
            if (node.getBranches() != null) {
                for (NodeConfig.Branch branch : node.getBranches()) {
                    if (branch != null && StrUtil.isNotBlank(branch.getNextNodeId())) {
                        referenced.add(branch.getNextNodeId());
                    }
                }
            }
        }
        List<String> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String nodeId : nodeById.keySet()) {
            if (!referenced.contains(nodeId)) {
                queue.add(nodeId);
            }
        }
        if (queue.isEmpty() && !nodeById.isEmpty()) {
            queue.add(nodeById.keySet().iterator().next());
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id) || !nodeById.containsKey(id)) {
                continue;
            }
            ordered.add(id);
            NodeConfig config = nodeById.get(id);
            if (StrUtil.isNotBlank(config.getNextNodeId())) {
                queue.add(config.getNextNodeId());
            }
            if (config.getBranches() != null) {
                for (NodeConfig.Branch branch : config.getBranches()) {
                    if (branch != null && StrUtil.isNotBlank(branch.getNextNodeId())) {
                        queue.add(branch.getNextNodeId());
                    }
                }
            }
        }
        for (String nodeId : nodeById.keySet()) {
            if (!visited.contains(nodeId)) {
                ordered.add(nodeId);
            }
        }
        return ordered;
    }
}
