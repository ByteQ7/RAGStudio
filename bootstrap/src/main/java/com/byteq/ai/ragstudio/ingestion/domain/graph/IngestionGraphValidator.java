package com.byteq.ai.ragstudio.ingestion.domain.graph;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 摄入流水线图校验器
 * <p>
 * 保存画布前执行；ERROR 拒绝保存，WARN 仅提示。
 * 分支语义为排他分支：一个节点有多条出边时，最多允许一条无条件的兜底（else）边。
 */
public final class IngestionGraphValidator {

    /** 节点数量上限 */
    public static final int MAX_NODES = 50;
    /** 连线数量上限 */
    public static final int MAX_EDGES = 100;
    /** 分支标签长度上限 */
    public static final int MAX_EDGE_LABEL_LENGTH = 200;
    /** 坐标绝对值上限 */
    public static final double MAX_COORDINATE = 100_000;

    private IngestionGraphValidator() {
    }

    /**
     * 校验结果
     *
     * @param errors   ERROR（拒绝保存）
     * @param warnings WARN（提示）
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {

        public boolean hasError() {
            return !errors.isEmpty();
        }

        public boolean isEmpty() {
            return errors.isEmpty() && warnings.isEmpty();
        }
    }

    /** 校验图结构 */
    public static ValidationResult validate(IngestionGraph graph) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (graph == null || graph.isEmpty()) {
            errors.add("流水线图为空，至少需要一个获取节点和一个结束节点");
            return new ValidationResult(errors, warnings);
        }
        List<IngestionGraph.Node> nodes = graph.nodes();
        List<IngestionGraph.Edge> edges = graph.edges() != null ? graph.edges() : List.of();

        if (nodes.size() > MAX_NODES) {
            errors.add("节点数量不能超过 " + MAX_NODES + "（当前 " + nodes.size() + "）");
        }
        if (edges.size() > MAX_EDGES) {
            errors.add("连线数量不能超过 " + MAX_EDGES + "（当前 " + edges.size() + "）");
        }

        Map<String, IngestionGraph.Node> nodeById = new HashMap<>();
        int startCount = 0;
        int endCount = 0;
        int processorCount = 0;
        for (IngestionGraph.Node node : nodes) {
            if (node == null || StrUtil.isBlank(node.id())) {
                errors.add("存在缺少 id 的节点");
                continue;
            }
            if (nodeById.put(node.id(), node) != null) {
                errors.add("节点 id 重复：" + node.id());
                continue;
            }
            String kind = node.kind();
            if (IngestionGraph.KIND_START.equals(kind)) {
                startCount++;
            } else if (IngestionGraph.KIND_END.equals(kind)) {
                endCount++;
            } else if (IngestionGraph.KIND_PROCESSOR.equals(kind)) {
                processorCount++;
                validateProcessor(node, errors);
            } else if (IngestionGraph.KIND_CONDITION.equals(kind)) {
                // 条件网关：无自身配置，分支条件在出边上
            } else {
                errors.add("节点 " + node.id() + " 的类型非法：" + kind);
            }
            if (node.x() != null && Math.abs(node.x()) > MAX_COORDINATE
                    || node.y() != null && Math.abs(node.y()) > MAX_COORDINATE) {
                warnings.add("节点 " + node.id() + " 坐标异常，将自动收敛到画布范围");
            }
        }
        if (startCount != 1) {
            errors.add("必须恰好有 1 个开始节点（当前 " + startCount + " 个）");
        }
        if (endCount != 1) {
            errors.add("必须恰好有 1 个结束节点（当前 " + endCount + " 个）");
        }
        if (processorCount == 0) {
            errors.add("流水线至少需要 1 个处理节点");
        }

        Map<String, List<IngestionGraph.Edge>> outgoing = new HashMap<>();
        Map<String, List<IngestionGraph.Edge>> incoming = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (IngestionGraph.Edge edge : edges) {
            if (edge == null || StrUtil.isBlank(edge.source()) || StrUtil.isBlank(edge.target())) {
                errors.add("存在缺少起点或终点的连线");
                continue;
            }
            if (StrUtil.isNotBlank(edge.id()) && !edgeIds.add(edge.id())) {
                errors.add("连线 id 重复：" + edge.id());
            }
            if (!nodeById.containsKey(edge.source())) {
                errors.add("连线 " + edgeRef(edge) + " 的起点节点不存在：" + edge.source());
                continue;
            }
            if (!nodeById.containsKey(edge.target())) {
                errors.add("连线 " + edgeRef(edge) + " 的终点节点不存在：" + edge.target());
                continue;
            }
            if (edge.source().equals(edge.target())) {
                errors.add("节点 " + edge.source() + " 存在自环连线");
                continue;
            }
            String pair = edge.source() + "->" + edge.target();
            if (!pairs.add(pair)) {
                errors.add("存在重复连线：" + pair);
                continue;
            }
            if (edge.label() != null && edge.label().length() > MAX_EDGE_LABEL_LENGTH) {
                errors.add("连线 " + edgeRef(edge) + " 的分支标签过长（上限 " + MAX_EDGE_LABEL_LENGTH + "）");
            }
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
        }

        for (IngestionGraph.Node node : nodeById.values()) {
            List<IngestionGraph.Edge> out = outgoing.getOrDefault(node.id(), List.of());
            List<IngestionGraph.Edge> in = incoming.getOrDefault(node.id(), List.of());
            switch (node.kind()) {
                case IngestionGraph.KIND_START -> {
                    if (!in.isEmpty()) {
                        errors.add("开始节点不能有入边");
                    }
                    if (out.size() != 1) {
                        errors.add("开始节点必须恰好有 1 条出边（当前 " + out.size() + " 条）");
                    }
                    if (!out.isEmpty() && out.get(0).condition() != null && !out.get(0).condition().isNull()) {
                        errors.add("开始节点的出边不能带条件");
                    }
                }
                case IngestionGraph.KIND_END -> {
                    if (!out.isEmpty()) {
                        errors.add("结束节点不能有出边");
                    }
                    if (in.isEmpty()) {
                        errors.add("结束节点必须有入边");
                    }
                }
                case IngestionGraph.KIND_CONDITION -> {
                    if (in.isEmpty()) {
                        errors.add("条件节点 " + nodeLabel(node) + " 必须有入边");
                    }
                    if (out.size() < 2) {
                        errors.add("条件节点 " + nodeLabel(node) + " 至少有 2 条出边（当前 " + out.size() + " 条）");
                    }
                    checkBranchConditions(node, out, errors);
                    // 条件节点的入边不能带条件（分支条件由条件节点的出边定义）
                    for (IngestionGraph.Edge edge : in) {
                        if (edge.condition() != null && !edge.condition().isNull()) {
                            errors.add("条件节点 " + nodeLabel(node) + " 的入边不能带条件（分支条件请配置在其出边上）");
                        }
                        IngestionGraph.Node from = nodeById.get(edge.source());
                        if (from != null && IngestionGraph.KIND_CONDITION.equals(from.kind())) {
                            errors.add("条件节点不能直接连接另一个条件节点：" + from.id() + " -> " + node.id());
                        }
                    }
                }
                case IngestionGraph.KIND_PROCESSOR -> {
                    if (in.isEmpty()) {
                        errors.add("处理节点 " + nodeLabel(node) + " 必须有入边（未连接前置节点）");
                    }
                    if (out.isEmpty()) {
                        errors.add("处理节点 " + nodeLabel(node) + " 必须有出边（未连接后续节点）");
                    }
                    if (out.size() > 1) {
                        checkBranchConditions(node, out, errors);
                    }
                }
                default -> {
                }
            }
        }

        // 环检测 + 可达性
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeById.keySet()) {
            inDegree.put(id, 0);
        }
        for (IngestionGraph.Edge edge : edges) {
            if (nodeById.containsKey(edge.source()) && nodeById.containsKey(edge.target())
                    && !edge.source().equals(edge.target())) {
                inDegree.merge(edge.target(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) {
                queue.add(e.getKey());
            }
        }
        int visited = 0;
        Set<String> reachable = new HashSet<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            visited++;
            reachable.add(id);
            for (IngestionGraph.Edge edge : outgoing.getOrDefault(id, List.of())) {
                if (nodeById.containsKey(edge.target())
                        && inDegree.merge(edge.target(), -1, Integer::sum) == 0) {
                    queue.add(edge.target());
                }
            }
        }
        if (visited < nodeById.size()) {
            List<String> cyclic = nodeById.keySet().stream()
                    .filter(id -> !reachable.contains(id))
                    .toList();
            errors.add("图中存在环路（或不可达节点）：" + cyclic);
        } else {
            String startId = nodeById.values().stream()
                    .filter(n -> IngestionGraph.KIND_START.equals(n.kind()))
                    .map(IngestionGraph.Node::id)
                    .findFirst().orElse(null);
            if (startId != null) {
                Set<String> fromStart = new HashSet<>();
                Deque<String> dfs = new ArrayDeque<>();
                dfs.push(startId);
                while (!dfs.isEmpty()) {
                    String id = dfs.pop();
                    if (!fromStart.add(id)) {
                        continue;
                    }
                    for (IngestionGraph.Edge edge : outgoing.getOrDefault(id, List.of())) {
                        dfs.push(edge.target());
                    }
                }
                List<String> unreachable = nodeById.keySet().stream()
                        .filter(id -> !fromStart.contains(id))
                        .toList();
                if (!unreachable.isEmpty()) {
                    warnings.add("以下节点从开始节点不可达：" + unreachable);
                }
            }
        }
        return new ValidationResult(errors, warnings);
    }

    /** 分支条件规则：多出边时最多一条无条件（兜底），且至少一条有条件 */
    private static void checkBranchConditions(IngestionGraph.Node node,
                                              List<IngestionGraph.Edge> out,
                                              List<String> errors) {
        int unconditional = 0;
        int conditional = 0;
        for (IngestionGraph.Edge edge : out) {
            if (edge.condition() == null || edge.condition().isNull()) {
                unconditional++;
            } else {
                conditional++;
            }
        }
        if (unconditional > 1) {
            errors.add("节点 " + nodeLabel(node) + " 有多条无条件出边（最多允许 1 条作为兜底分支）");
        }
        if (conditional == 0) {
            errors.add("节点 " + nodeLabel(node) + " 的多条出边必须至少有一条配置分支条件");
        }
    }

    private static void validateProcessor(IngestionGraph.Node node, List<String> errors) {
        IngestionGraph.NodeData data = node.data();
        String nodeType = data != null ? data.nodeType() : null;
        if (StrUtil.isBlank(nodeType)) {
            errors.add("处理节点 " + node.id() + " 缺少节点类型");
            return;
        }
        try {
            IngestionNodeType.fromValue(nodeType);
        } catch (IllegalArgumentException e) {
            errors.add("处理节点 " + node.id() + " 的类型非法：" + nodeType);
        }
    }

    private static String nodeLabel(IngestionGraph.Node node) {
        IngestionGraph.NodeData data = node.data();
        if (data != null && StrUtil.isNotBlank(data.nodeType())) {
            return "[" + data.nodeType() + "]";
        }
        if (data != null && StrUtil.isNotBlank(data.note())) {
            return "[" + StrUtil.subPre(data.note(), 20) + "]";
        }
        return node.id();
    }

    private static String edgeRef(IngestionGraph.Edge edge) {
        return StrUtil.isNotBlank(edge.id()) ? edge.id() : (edge.source() + "->" + edge.target());
    }
}
