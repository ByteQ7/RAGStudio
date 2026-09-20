package com.byteq.ai.ragstudio.rag.workflow.graph;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowJson;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流图结构校验器
 * <p>
 * 保存画布前执行，ERROR 拒绝入库；WARN 仅提示（不影响保存）。
 * 校验规则见 {@code docs/workflow-canvas-design.md} §2.4。
 */
public final class WorkflowGraphValidator {

    /** 节点数量上限（防爆炸） */
    public static final int MAX_NODES = 50;
    /** 连线数量上限 */
    public static final int MAX_EDGES = 100;
    /** 坐标绝对值上限（超出视为异常） */
    public static final double MAX_COORDINATE = 100_000;
    /** 条件表达式长度上限 */
    public static final int MAX_EXPRESSION_LENGTH = 500;
    /** 分支标签长度上限 */
    public static final int MAX_EDGE_LABEL_LENGTH = 200;

    private WorkflowGraphValidator() {
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
    public static ValidationResult validate(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (graph == null || graph.isEmpty()) {
            errors.add("图为空，至少需要一个开始节点和一个结束节点");
            return new ValidationResult(errors, warnings);
        }
        List<WorkflowGraph.Node> nodes = graph.nodes();
        List<WorkflowGraph.Edge> edges = graph.edges() != null ? graph.edges() : List.of();

        if (nodes.size() > MAX_NODES) {
            errors.add("节点数量不能超过 " + MAX_NODES + "（当前 " + nodes.size() + "）");
        }
        if (edges.size() > MAX_EDGES) {
            errors.add("连线数量不能超过 " + MAX_EDGES + "（当前 " + edges.size() + "）");
        }

        // 节点基础校验 + ID 唯一性
        Map<String, WorkflowGraph.Node> nodeById = new HashMap<>();
        int startCount = 0;
        int endCount = 0;
        for (WorkflowGraph.Node node : nodes) {
            if (node == null || StrUtil.isBlank(node.id())) {
                errors.add("存在缺少 id 的节点");
                continue;
            }
            if (nodeById.put(node.id(), node) != null) {
                errors.add("节点 id 重复：" + node.id());
                continue;
            }
            String kind = node.kind();
            if (!WorkflowGraph.KIND_START.equals(kind) && !WorkflowGraph.KIND_STEP.equals(kind)
                    && !WorkflowGraph.KIND_CONDITION.equals(kind) && !WorkflowGraph.KIND_END.equals(kind)) {
                errors.add("节点 " + node.id() + " 的类型非法：" + kind);
                continue;
            }
            if (WorkflowGraph.KIND_START.equals(kind)) {
                startCount++;
            } else if (WorkflowGraph.KIND_END.equals(kind)) {
                endCount++;
            } else if (WorkflowGraph.KIND_STEP.equals(kind)) {
                validateStep(node, errors);
            } else {
                validateCondition(node, errors);
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

        // 入度/出度统计
        Map<String, List<WorkflowGraph.Edge>> outgoing = new HashMap<>();
        Map<String, List<WorkflowGraph.Edge>> incoming = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (WorkflowGraph.Edge edge : edges) {
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
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
            if (edge.label() != null && edge.label().length() > MAX_EDGE_LABEL_LENGTH) {
                errors.add("连线 " + edgeRef(edge) + " 的分支标签过长（上限 " + MAX_EDGE_LABEL_LENGTH + "）");
            }
        }

        // 出入度约束（开始/结束/条件/步骤）
        for (WorkflowGraph.Node node : nodeById.values()) {
            List<WorkflowGraph.Edge> out = outgoing.getOrDefault(node.id(), List.of());
            List<WorkflowGraph.Edge> in = incoming.getOrDefault(node.id(), List.of());
            switch (node.kind()) {
                case WorkflowGraph.KIND_START -> {
                    if (!in.isEmpty()) {
                        errors.add("开始节点不能有入边");
                    }
                    if (out.size() != 1) {
                        errors.add("开始节点必须恰好有 1 条出边（当前 " + out.size() + " 条）");
                    }
                }
                case WorkflowGraph.KIND_END -> {
                    if (!out.isEmpty()) {
                        errors.add("结束节点不能有出边");
                    }
                    if (in.isEmpty()) {
                        errors.add("结束节点必须有入边");
                    }
                }
                case WorkflowGraph.KIND_CONDITION -> {
                    if (out.size() < 2) {
                        errors.add("条件节点 " + nodeLabel(node) + " 至少有 2 条出边（当前 " + out.size() + " 条）");
                    }
                    for (WorkflowGraph.Edge edge : out) {
                        if (StrUtil.isBlank(edge.label())) {
                            errors.add("条件节点 " + nodeLabel(node) + " 的出边必须填写分支标签（如：是 / 否）");
                        }
                    }
                    if (in.isEmpty()) {
                        errors.add("条件节点 " + nodeLabel(node) + " 必须有入边");
                    }
                }
                case WorkflowGraph.KIND_STEP -> {
                    if (out.isEmpty()) {
                        errors.add("步骤节点 " + nodeLabel(node) + " 必须有出边（未连接后续节点）");
                    }
                    if (in.isEmpty()) {
                        errors.add("步骤节点 " + nodeLabel(node) + " 必须有入边（未连接前置节点）");
                    }
                }
                default -> {
                }
            }
        }

        // 环检测 + 可达性（Kahn 拓扑排序）
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeById.keySet()) {
            inDegree.put(id, 0);
        }
        for (WorkflowGraph.Edge edge : edges) {
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
            for (WorkflowGraph.Edge edge : outgoing.getOrDefault(id, List.of())) {
                if (!nodeById.containsKey(edge.target())) {
                    continue;
                }
                if (inDegree.merge(edge.target(), -1, Integer::sum) == 0) {
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
            // 可达性告警：从 start 出发无法到达的节点
            String startId = nodeById.values().stream()
                    .filter(n -> WorkflowGraph.KIND_START.equals(n.kind()))
                    .map(WorkflowGraph.Node::id)
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
                    for (WorkflowGraph.Edge edge : outgoing.getOrDefault(id, List.of())) {
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

    private static void validateStep(WorkflowGraph.Node node, List<String> errors) {
        WorkflowGraph.NodeData data = node.data();
        String action = data != null ? data.action() : null;
        if (StrUtil.isBlank(action)) {
            errors.add("步骤节点 " + nodeLabel(node) + " 缺少动作描述");
            return;
        }
        if (action.length() > WorkflowJson.MAX_ACTION_LENGTH) {
            errors.add("步骤节点 " + nodeLabel(node) + " 动作描述过长（上限 " + WorkflowJson.MAX_ACTION_LENGTH + "）");
        }
        if (data.tool() != null && data.tool().length() > WorkflowJson.MAX_TOOL_LENGTH) {
            errors.add("步骤节点 " + nodeLabel(node) + " 工具名过长（上限 " + WorkflowJson.MAX_TOOL_LENGTH + "）");
        }
        if (data.when() != null && data.when().length() > WorkflowJson.MAX_WHEN_LENGTH) {
            errors.add("步骤节点 " + nodeLabel(node) + " 执行条件过长（上限 " + WorkflowJson.MAX_WHEN_LENGTH + "）");
        }
    }

    private static void validateCondition(WorkflowGraph.Node node, List<String> errors) {
        WorkflowGraph.NodeData data = node.data();
        String expression = data != null ? data.expression() : null;
        if (StrUtil.isBlank(expression)) {
            errors.add("条件节点 " + nodeLabel(node) + " 缺少条件表达式");
        } else if (expression.length() > MAX_EXPRESSION_LENGTH) {
            errors.add("条件节点 " + nodeLabel(node) + " 表达式过长（上限 " + MAX_EXPRESSION_LENGTH + "）");
        }
    }

    private static String nodeLabel(WorkflowGraph.Node node) {
        WorkflowGraph.NodeData data = node.data();
        if (data != null && StrUtil.isNotBlank(data.action())) {
            return "[" + StrUtil.subPre(data.action(), 20) + "]";
        }
        if (data != null && StrUtil.isNotBlank(data.expression())) {
            return "[" + StrUtil.subPre(data.expression(), 20) + "]";
        }
        return node.id();
    }

    private static String edgeRef(WorkflowGraph.Edge edge) {
        return StrUtil.isNotBlank(edge.id()) ? edge.id() : (edge.source() + "->" + edge.target());
    }
}
