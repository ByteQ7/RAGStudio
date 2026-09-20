package com.byteq.ai.ragstudio.rag.workflow.graph;

import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.workflow.WorkflowJson;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流图编译器（graph → 线性 steps）
 * <p>
 * 运行态仍使用"线性步骤 + 条件"的软执行模型，本编译器把画布图无损降维为
 * Agent 可读的步骤列表：
 * <ul>
 *   <li>按拓扑序输出 step 节点（start / end / condition 不产出步骤）；</li>
 *   <li>路径条件沿 condition 出边的 label 累积（如 {@code （物流是否延误：是）}），
 *       多级条件用「且」连接；汇聚节点（多入边）用「或」连接；</li>
 *   <li>step 节点自身的 {@code when}（旧数据迁移字段）与路径条件用「且」合并。</li>
 * </ul>
 * 条件使用自然语言描述而非形式化逻辑——运行态是 LLM 软执行，可读性优先。
 */
public final class WorkflowGraphCompiler {

    /** 编译结果 when 上限（超出提示用户简化图） */
    private static final int MAX_COMPILED_WHEN_LENGTH = WorkflowJson.MAX_WHEN_LENGTH;

    private WorkflowGraphCompiler() {
    }

    /**
     * 编译为线性步骤（调用方需先通过 {@link WorkflowGraphValidator} 校验）
     *
     * @throws ClientException 图结构不合法或编译结果超长时抛出
     */
    public static List<WorkflowDefinition.WorkflowStep> compile(WorkflowGraph graph) {
        if (graph == null || graph.isEmpty()) {
            throw new ClientException("图为空，无法编译工作流步骤");
        }
        List<WorkflowGraph.Node> nodes = graph.nodes();
        List<WorkflowGraph.Edge> edges = graph.edges() != null ? graph.edges() : List.of();

        Map<String, WorkflowGraph.Node> nodeById = new LinkedHashMap<>();
        for (WorkflowGraph.Node node : nodes) {
            nodeById.put(node.id(), node);
        }
        Map<String, List<WorkflowGraph.Edge>> outgoing = new HashMap<>();
        Map<String, List<WorkflowGraph.Edge>> incoming = new HashMap<>();
        for (WorkflowGraph.Edge edge : edges) {
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
        }

        // 拓扑排序（Kahn；同层按原节点顺序，保证编译结果稳定）
        List<String> ordered = topologicalOrder(nodes, nodeById, outgoing, incoming);

        // 逐节点计算路径条件
        Map<String, String> pathCondition = new HashMap<>();
        for (String id : ordered) {
            WorkflowGraph.Node node = nodeById.get(id);
            if (WorkflowGraph.KIND_START.equals(node.kind())) {
                pathCondition.put(id, null);
                continue;
            }
            String condition = mergeIncomingConditions(node, incoming.getOrDefault(id, List.of()),
                    nodeById, pathCondition);
            pathCondition.put(id, condition);
        }

        // 输出步骤
        List<WorkflowDefinition.WorkflowStep> steps = new ArrayList<>();
        for (String id : ordered) {
            WorkflowGraph.Node node = nodeById.get(id);
            if (!WorkflowGraph.KIND_STEP.equals(node.kind())) {
                continue;
            }
            WorkflowGraph.NodeData data = node.data();
            String when = and(pathCondition.get(id), data != null ? data.when() : null);
            if (when != null && when.length() > MAX_COMPILED_WHEN_LENGTH) {
                throw new ClientException("工作流分支嵌套过深，编译后的步骤条件超过 "
                        + MAX_COMPILED_WHEN_LENGTH + " 字符，请简化分支结构");
            }
            steps.add(new WorkflowDefinition.WorkflowStep(data.action(), data.tool(), when));
        }
        if (steps.isEmpty()) {
            throw new ClientException("图中没有可执行的步骤节点");
        }
        return steps;
    }

    /**
     * 合并单个节点的全部入边条件：
     * 每条入边贡献 {@code 前驱路径条件 [且 分支标签]}，多条入边之间取「或」；
     * 任一无条件路径 → 整体无条件（null）。
     */
    private static String mergeIncomingConditions(WorkflowGraph.Node node,
                                                  List<WorkflowGraph.Edge> incoming,
                                                  Map<String, WorkflowGraph.Node> nodeById,
                                                  Map<String, String> pathCondition) {
        if (incoming.isEmpty()) {
            // 孤立节点（校验阶段已拦截），编译时按无条件处理
            return null;
        }
        Set<String> disjuncts = new LinkedHashSet<>();
        for (WorkflowGraph.Edge edge : incoming) {
            WorkflowGraph.Node from = nodeById.get(edge.source());
            String base = pathCondition.get(edge.source());
            String combined = base;
            if (from != null && WorkflowGraph.KIND_CONDITION.equals(from.kind())
                    && StrUtil.isNotBlank(edge.label())) {
                String expression = from.data() != null ? from.data().expression() : null;
                String branch = "（" + StrUtil.blankToDefault(expression, "条件") + "：" + edge.label().trim() + "）";
                combined = and(base, branch);
            }
            if (combined == null) {
                // 存在无条件路径 → 该节点总是执行
                return null;
            }
            disjuncts.add(combined);
        }
        return String.join(" 或 ", disjuncts);
    }

    /** AND 合并两个条件（任一为空返回另一个） */
    private static String and(String left, String right) {
        if (StrUtil.isBlank(left)) {
            return StrUtil.isBlank(right) ? null : right.trim();
        }
        if (StrUtil.isBlank(right)) {
            return left.trim();
        }
        return left.trim() + " 且 " + right.trim();
    }

    /** Kahn 拓扑排序；同层按 nodes 原顺序出队，保证结果稳定可复现 */
    private static List<String> topologicalOrder(List<WorkflowGraph.Node> nodes,
                                                 Map<String, WorkflowGraph.Node> nodeById,
                                                 Map<String, List<WorkflowGraph.Edge>> outgoing,
                                                 Map<String, List<WorkflowGraph.Edge>> incoming) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (WorkflowGraph.Node node : nodes) {
            inDegree.put(node.id(), 0);
        }
        for (WorkflowGraph.Edge edge : outgoing.values().stream().flatMap(List::stream).toList()) {
            if (nodeById.containsKey(edge.target())) {
                inDegree.merge(edge.target(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (WorkflowGraph.Node node : nodes) {
            if (inDegree.get(node.id()) == 0) {
                queue.add(node.id());
            }
        }
        List<String> ordered = new ArrayList<>(nodeById.size());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            ordered.add(id);
            for (WorkflowGraph.Edge edge : outgoing.getOrDefault(id, List.of())) {
                if (!nodeById.containsKey(edge.target())) {
                    continue;
                }
                if (inDegree.merge(edge.target(), -1, Integer::sum) == 0) {
                    queue.add(edge.target());
                }
            }
        }
        if (ordered.size() < nodeById.size()) {
            throw new ClientException("工作流图存在环路，无法编译为线性步骤");
        }
        return ordered;
    }
}
