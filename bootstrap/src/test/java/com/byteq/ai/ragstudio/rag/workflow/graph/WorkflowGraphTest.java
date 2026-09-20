package com.byteq.ai.ragstudio.rag.workflow.graph;

import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.workflow.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流图核心逻辑测试：结构校验 / 编译为线性步骤 / 旧数据（steps）转图。
 */
class WorkflowGraphTest {

    // ==================== 工具方法 ====================

    private static WorkflowGraph.Node start() {
        return new WorkflowGraph.Node("start", WorkflowGraph.KIND_START, 0.0, 0.0,
                new WorkflowGraph.NodeData(null, null, null, null));
    }

    private static WorkflowGraph.Node end() {
        return new WorkflowGraph.Node("end", WorkflowGraph.KIND_END, 500.0, 0.0,
                new WorkflowGraph.NodeData(null, null, null, null));
    }

    private static WorkflowGraph.Node step(String id, String action, String tool, String when) {
        return new WorkflowGraph.Node(id, WorkflowGraph.KIND_STEP, 100.0, 0.0,
                new WorkflowGraph.NodeData(action, tool, when, null));
    }

    private static WorkflowGraph.Node condition(String id, String expression) {
        return new WorkflowGraph.Node(id, WorkflowGraph.KIND_CONDITION, 200.0, 0.0,
                new WorkflowGraph.NodeData(null, null, null, expression));
    }

    private static WorkflowGraph.Edge edge(String id, String source, String target, String label) {
        return new WorkflowGraph.Edge(id, source, target, label);
    }

    private static WorkflowGraph graph(List<WorkflowGraph.Node> nodes, List<WorkflowGraph.Edge> edges) {
        return new WorkflowGraph(1, nodes, edges);
    }

    // ==================== 校验 ====================

    @Test
    void validLinearGraphPasses() {
        WorkflowGraph g = graph(
                List.of(start(), step("n1", "查询订单", "order_query", null), end()),
                List.of(edge("e1", "start", "n1", null), edge("e2", "n1", "end", null)));
        WorkflowGraphValidator.ValidationResult result = WorkflowGraphValidator.validate(g);
        assertFalse(result.hasError(), "合法线性图不应报错: " + result.errors());
    }

    @Test
    void validBranchGraphPasses() {
        WorkflowGraph g = graph(
                List.of(start(), condition("c1", "物流是否延误"),
                        step("n2", "发起赔付", null, null), step("n3", "转质检", null, null), end()),
                List.of(edge("e1", "start", "c1", null),
                        edge("e2", "c1", "n2", "是"), edge("e3", "c1", "n3", "否"),
                        edge("e4", "n2", "end", null), edge("e5", "n3", "end", null)));
        WorkflowGraphValidator.ValidationResult result = WorkflowGraphValidator.validate(g);
        assertFalse(result.hasError(), "合法分支图不应报错: " + result.errors());
    }

    @Test
    void rejectsMissingStartOrEnd() {
        WorkflowGraph noStart = graph(
                List.of(step("n1", "a", null, null), end()),
                List.of(edge("e1", "n1", "end", null)));
        assertTrue(WorkflowGraphValidator.validate(noStart).hasError());

        WorkflowGraph noEnd = graph(
                List.of(start(), step("n1", "a", null, null)),
                List.of(edge("e1", "start", "n1", null)));
        assertTrue(WorkflowGraphValidator.validate(noEnd).hasError());
    }

    @Test
    void rejectsCycle() {
        WorkflowGraph g = graph(
                List.of(start(), step("n1", "a", null, null), step("n2", "b", null, null), end()),
                List.of(edge("e1", "start", "n1", null), edge("e2", "n1", "n2", null),
                        edge("e3", "n2", "n1", null), edge("e4", "n2", "end", null)));
        WorkflowGraphValidator.ValidationResult result = WorkflowGraphValidator.validate(g);
        assertTrue(result.hasError());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("环路")), result.errors().toString());
    }

    @Test
    void rejectsConditionWithoutLabelOrSingleBranch() {
        WorkflowGraph noLabel = graph(
                List.of(start(), condition("c1", "条件"), step("n1", "a", null, null), end()),
                List.of(edge("e1", "start", "c1", null), edge("e2", "c1", "n1", null),
                        edge("e3", "n1", "end", null)));
        assertTrue(WorkflowGraphValidator.validate(noLabel).hasError());

        WorkflowGraph single = graph(
                List.of(start(), condition("c1", "条件"), step("n1", "a", null, null), end()),
                List.of(edge("e1", "start", "c1", null), edge("e2", "c1", "n1", "是"),
                        edge("e3", "n1", "end", null)));
        assertTrue(WorkflowGraphValidator.validate(single).hasError());
    }

    @Test
    void rejectsIsolatedAndBlankActionNodes() {
        WorkflowGraph isolated = graph(
                List.of(start(), step("n1", "a", null, null), step("n2", "b", null, null), end()),
                List.of(edge("e1", "start", "n1", null), edge("e2", "n1", "end", null)));
        assertTrue(WorkflowGraphValidator.validate(isolated).hasError());

        WorkflowGraph blank = graph(
                List.of(start(), step("n1", "  ", null, null), end()),
                List.of(edge("e1", "start", "n1", null), edge("e2", "n1", "end", null)));
        assertTrue(WorkflowGraphValidator.validate(blank).hasError());
    }

    @Test
    void rejectsTooManyNodes() {
        java.util.List<WorkflowGraph.Node> nodes = new java.util.ArrayList<>();
        java.util.List<WorkflowGraph.Edge> edges = new java.util.ArrayList<>();
        nodes.add(start());
        String prev = "start";
        for (int i = 1; i <= WorkflowGraphValidator.MAX_NODES; i++) {
            String id = "n" + i;
            nodes.add(step(id, "步骤" + i, null, null));
            edges.add(edge("e" + i, prev, id, null));
            prev = id;
        }
        nodes.add(end());
        edges.add(edge("e-end", prev, "end", null));
        assertTrue(WorkflowGraphValidator.validate(graph(nodes, edges)).hasError());
    }

    // ==================== 编译 ====================

    @Test
    void compileLinearGraph() {
        WorkflowGraph g = graph(
                List.of(start(), step("n1", "查询订单", "order_query", null),
                        step("n2", "同步用户", null, null), end()),
                List.of(edge("e1", "start", "n1", null), edge("e2", "n1", "n2", null),
                        edge("e3", "n2", "end", null)));
        List<WorkflowDefinition.WorkflowStep> steps = WorkflowGraphCompiler.compile(g);
        assertEquals(2, steps.size());
        assertEquals("查询订单", steps.get(0).action());
        assertEquals("order_query", steps.get(0).tool());
        assertNull(steps.get(0).when());
        assertEquals("同步用户", steps.get(1).action());
    }

    @Test
    void compileBranchGraphGeneratesReadableConditions() {
        WorkflowGraph g = graph(
                List.of(start(), condition("c1", "物流是否延误"),
                        step("n2", "发起赔付", null, null), step("n3", "转质检", null, null), end()),
                List.of(edge("e1", "start", "c1", null),
                        edge("e2", "c1", "n2", "是"), edge("e3", "c1", "n3", "否"),
                        edge("e4", "n2", "end", null), edge("e5", "n3", "end", null)));
        List<WorkflowDefinition.WorkflowStep> steps = WorkflowGraphCompiler.compile(g);
        assertEquals(2, steps.size());
        assertEquals("发起赔付", steps.get(0).action());
        assertEquals("（物流是否延误：是）", steps.get(0).when());
        assertEquals("（物流是否延误：否）", steps.get(1).when());
    }

    @Test
    void compileMergesConditionAndStepWhen() {
        // 旧数据迁移场景：step 自带 when + 路径条件，编译时应 AND 合并
        WorkflowGraph g = graph(
                List.of(start(), condition("c1", "已支付"),
                        step("n2", "发起赔付", null, "物流延误"), end()),
                List.of(edge("e1", "start", "c1", null), edge("e2", "c1", "n2", "是"),
                        edge("e3", "n2", "end", null)));
        List<WorkflowDefinition.WorkflowStep> steps = WorkflowGraphCompiler.compile(g);
        assertEquals(1, steps.size());
        assertEquals("（已支付：是） 且 物流延误", steps.get(0).when());
    }

    @Test
    void compileMergeNodeBecomesUnconditionalOrConditional() {
        // n4 汇聚两条分支：条件应为「或」
        WorkflowGraph g = graph(
                List.of(start(), condition("c1", "条件A"),
                        step("n2", "分支1", null, null), step("n3", "分支2", null, null),
                        step("n4", "汇聚", null, null), end()),
                List.of(edge("e1", "start", "c1", null),
                        edge("e2", "c1", "n2", "是"), edge("e3", "c1", "n3", "否"),
                        edge("e4", "n2", "n4", null), edge("e5", "n3", "n4", null),
                        edge("e6", "n4", "end", null)));
        List<WorkflowDefinition.WorkflowStep> steps = WorkflowGraphCompiler.compile(g);
        assertEquals(3, steps.size());
        String merged = steps.get(2).when();
        assertNotNull(merged);
        assertTrue(merged.contains("或"), merged);
    }

    @Test
    void compileRejectsGraphWithoutStepNodes() {
        WorkflowGraph g = graph(
                List.of(start(), end()),
                List.of(edge("e1", "start", "end", null)));
        assertThrows(ClientException.class, () -> WorkflowGraphCompiler.compile(g));
    }

    // ==================== 旧数据兼容（steps → graph） ====================

    @Test
    void factoryBuildsLinearGraphFromSteps() {
        List<WorkflowDefinition.WorkflowStep> steps = List.of(
                new WorkflowDefinition.WorkflowStep("查询订单", "order_query", null),
                new WorkflowDefinition.WorkflowStep("发起赔付", null, "物流延误"));
        WorkflowGraph graph = WorkflowGraphFactory.fromSteps(steps);

        assertEquals(4, graph.nodeCount()); // start + 2 step + end
        assertEquals(3, graph.edges().size());
        assertEquals(WorkflowGraph.KIND_START, graph.nodes().get(0).kind());
        assertEquals(WorkflowGraph.KIND_END, graph.nodes().get(3).kind());
        // when 保留在 step 节点上（不丢语义）
        assertEquals("物流延误", graph.nodes().get(2).data().when());
        assertFalse(WorkflowGraphValidator.validate(graph).hasError());
    }

    @Test
    void factoryHandlesEmptySteps() {
        WorkflowGraph graph = WorkflowGraphFactory.fromSteps(List.of());
        assertEquals(2, graph.nodeCount()); // start + end
        // 只有 start/end 的图不是合法工作流（无步骤），但结构上可通过校验
        WorkflowGraphValidator.ValidationResult result = WorkflowGraphValidator.validate(graph);
        assertFalse(result.hasError(), result.errors().toString());
    }

    @Test
    void stepsToGraphToStepsRoundTrip() {
        List<WorkflowDefinition.WorkflowStep> original = List.of(
                new WorkflowDefinition.WorkflowStep("步骤一", "tool_a", null),
                new WorkflowDefinition.WorkflowStep("步骤二", null, "条件X"));
        WorkflowGraph graph = WorkflowGraphFactory.fromSteps(original);
        List<WorkflowDefinition.WorkflowStep> compiled = WorkflowGraphCompiler.compile(graph);

        assertEquals(original.size(), compiled.size());
        assertEquals(original.get(0).action(), compiled.get(0).action());
        assertEquals(original.get(0).tool(), compiled.get(0).tool());
        assertEquals(original.get(1).action(), compiled.get(1).action());
        assertEquals(original.get(1).when(), compiled.get(1).when());
    }

    // ==================== JSON 编解码 ====================

    @Test
    void graphJsonRoundTrip() {
        WorkflowGraph g = graph(
                List.of(start(), condition("c1", "条件"), step("n1", "动作", "tool", null), end()),
                List.of(edge("e1", "start", "c1", null), edge("e2", "c1", "n1", "是"),
                        edge("e3", "n1", "end", null)));
        String json = WorkflowGraphJson.toJson(g);
        assertNotNull(json);
        WorkflowGraph parsed = WorkflowGraphJson.parse(json);
        assertNotNull(parsed);
        assertEquals(4, parsed.nodeCount());
        assertEquals(3, parsed.edges().size());
        assertEquals("条件", parsed.nodes().get(1).data().expression());
        assertEquals("是", parsed.edges().get(1).label());
    }

    @Test
    void graphJsonInvalidReturnsNull() {
        assertNull(WorkflowGraphJson.parse(null));
        assertNull(WorkflowGraphJson.parse(""));
        assertNull(WorkflowGraphJson.parse("{broken"));
    }
}
