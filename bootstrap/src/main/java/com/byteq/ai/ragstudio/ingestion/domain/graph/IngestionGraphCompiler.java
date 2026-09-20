package com.byteq.ai.ragstudio.ingestion.domain.graph;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄入流水线图编译器（graph → NodeConfig 列表 + 排他分支）
 * <p>
 * 编译规则：
 * <ul>
 *   <li>按拓扑序输出 processor 节点；start / end / condition 不产出运行节点；</li>
 *   <li><b>条件网关内联</b>：processor 的出边指向 condition 节点时，
 *       把该 condition 节点的出边（含分支条件）提升为 processor 的分支；</li>
 *   <li>分支条件 = 出边 condition；无条件的出边作为兜底（写入 {@code nextNodeId}），
 *       最多一条（校验阶段保证）；</li>
 *   <li>单条无条件出边 → 直接写 {@code nextNodeId}，不含 branches（与旧模型完全一致）。</li>
 * </ul>
 */
public final class IngestionGraphCompiler {

    private IngestionGraphCompiler() {
    }

    /**
     * 编译为引擎可执行的节点配置列表
     *
     * @throws ClientException 图结构不合法时抛出
     */
    public static List<NodeConfig> compile(IngestionGraph graph) {
        if (graph == null || graph.isEmpty()) {
            throw new ClientException("流水线图为空，无法编译");
        }
        Map<String, IngestionGraph.Node> nodeById = new LinkedHashMap<>();
        for (IngestionGraph.Node node : graph.nodes()) {
            nodeById.put(node.id(), node);
        }
        Map<String, List<IngestionGraph.Edge>> outgoing = new HashMap<>();
        Map<String, List<IngestionGraph.Edge>> incoming = new HashMap<>();
        for (IngestionGraph.Edge edge : graph.edges() != null ? graph.edges() : List.<IngestionGraph.Edge>of()) {
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
        }

        List<String> ordered = topologicalOrder(graph.nodes(), nodeById, outgoing);
        List<NodeConfig> configs = new ArrayList<>();
        for (String id : ordered) {
            IngestionGraph.Node node = nodeById.get(id);
            if (!IngestionGraph.KIND_PROCESSOR.equals(node.kind())) {
                continue;
            }
            IngestionGraph.NodeData data = node.data();
            if (data == null || StrUtil.isBlank(data.nodeType())) {
                throw new ClientException("处理节点 " + id + " 缺少节点类型");
            }
            configs.add(NodeConfig.builder()
                    .nodeId(id)
                    .nodeType(data.nodeType().trim().toLowerCase())
                    .settings(data.settings())
                    .condition(data.condition())
                    .nextNodeId(resolveFallback(id, outgoing, nodeById))
                    .branches(resolveBranches(id, outgoing, nodeById))
                    .build());
        }
        if (configs.isEmpty()) {
            throw new ClientException("流水线图中没有处理节点");
        }
        return configs;
    }

    /**
     * 解析分支列表：processor 出边（或经条件网关展开后的出边）中带条件的部分。
     * 返回 null 表示无分支（纯线性）。
     */
    private static List<NodeConfig.Branch> resolveBranches(String nodeId,
                                                           Map<String, List<IngestionGraph.Edge>> outgoing,
                                                           Map<String, IngestionGraph.Node> nodeById) {
        List<ResolvedEdge> edges = resolveOutgoing(nodeId, outgoing, nodeById);
        List<NodeConfig.Branch> branches = new ArrayList<>();
        for (ResolvedEdge edge : edges) {
            if (edge.condition() != null && !edge.condition().isNull()) {
                branches.add(NodeConfig.Branch.builder()
                        .condition(edge.condition())
                        .nextNodeId(edge.target())
                        .build());
            }
        }
        return branches.isEmpty() ? null : branches;
    }

    /** 解析兜底后继：出边中无条件的部分（最多一条） */
    private static String resolveFallback(String nodeId,
                                          Map<String, List<IngestionGraph.Edge>> outgoing,
                                          Map<String, IngestionGraph.Node> nodeById) {
        for (ResolvedEdge edge : resolveOutgoing(nodeId, outgoing, nodeById)) {
            if (edge.condition() == null || edge.condition().isNull()) {
                return normalizeTarget(edge.target());
            }
        }
        return null;
    }

    /** end 节点是图的结构标记，运行时映射为 null（流水线正常结束） */
    private static String normalizeTarget(String target) {
        return IngestionGraph.KIND_END.equals(target) ? null : target;
    }

    /**
     * 展开节点的有效出边：直接出边 + 穿过条件网关的出边。
     * 条件网关本身不产出运行节点，其出边条件即分支条件。
     */
    private static List<ResolvedEdge> resolveOutgoing(String nodeId,
                                                       Map<String, List<IngestionGraph.Edge>> outgoing,
                                                       Map<String, IngestionGraph.Node> nodeById) {
        List<ResolvedEdge> result = new ArrayList<>();
        for (IngestionGraph.Edge edge : outgoing.getOrDefault(nodeId, List.of())) {
            IngestionGraph.Node target = nodeById.get(edge.target());
            if (target == null) {
                continue;
            }
            if (IngestionGraph.KIND_CONDITION.equals(target.kind())) {
                for (IngestionGraph.Edge sub : outgoing.getOrDefault(target.id(), List.of())) {
                    result.add(new ResolvedEdge(sub.condition(), sub.target()));
                }
            } else {
                result.add(new ResolvedEdge(edge.condition(), edge.target()));
            }
        }
        return result;
    }

    /** Kahn 拓扑排序（processor 与网关参与排序；同层按原节点顺序，结果稳定） */
    private static List<String> topologicalOrder(List<IngestionGraph.Node> nodes,
                                                 Map<String, IngestionGraph.Node> nodeById,
                                                 Map<String, List<IngestionGraph.Edge>> outgoing) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (IngestionGraph.Node node : nodes) {
            inDegree.put(node.id(), 0);
        }
        for (Map.Entry<String, List<IngestionGraph.Edge>> entry : outgoing.entrySet()) {
            for (IngestionGraph.Edge edge : entry.getValue()) {
                if (nodeById.containsKey(edge.target())) {
                    inDegree.merge(edge.target(), 1, Integer::sum);
                }
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (IngestionGraph.Node node : nodes) {
            if (inDegree.get(node.id()) == 0) {
                queue.add(node.id());
            }
        }
        List<String> ordered = new ArrayList<>(nodeById.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            ordered.add(id);
            for (IngestionGraph.Edge edge : outgoing.getOrDefault(id, List.of())) {
                if (!nodeById.containsKey(edge.target())) {
                    continue;
                }
                if (inDegree.merge(edge.target(), -1, Integer::sum) == 0) {
                    queue.add(edge.target());
                }
            }
        }
        if (ordered.size() < nodeById.size()) {
            throw new ClientException("流水线图存在环路，无法编译");
        }
        return ordered;
    }

    /** 展开后的有效边 */
    private record ResolvedEdge(JsonNode condition, String target) {
    }
}
