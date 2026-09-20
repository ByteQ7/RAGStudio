package com.byteq.ai.ragstudio.ingestion.domain.graph;

import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.ingestion.engine.ConditionEvaluator;
import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import com.byteq.ai.ragstudio.ingestion.engine.NodeOutputExtractor;
import com.byteq.ai.ragstudio.ingestion.node.IngestionNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 摄入流水线图核心逻辑测试：结构校验 / 编译（含条件网关内联）/ 旧数据转图 / 引擎排他分支执行。
 */
class IngestionGraphTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 构造工具 ====================

    private static IngestionGraph.Node start() {
        return new IngestionGraph.Node("start", IngestionGraph.KIND_START, 0.0, 0.0,
                new IngestionGraph.NodeData(null, null, null, null));
    }

    private static IngestionGraph.Node end() {
        return new IngestionGraph.Node("end", IngestionGraph.KIND_END, 1000.0, 0.0,
                new IngestionGraph.NodeData(null, null, null, null));
    }

    private static IngestionGraph.Node processor(String id, String nodeType) {
        return processor(id, nodeType, null);
    }

    private static IngestionGraph.Node processor(String id, String nodeType, JsonNode settings) {
        return new IngestionGraph.Node(id, IngestionGraph.KIND_PROCESSOR, 100.0, 0.0,
                new IngestionGraph.NodeData(nodeType, settings, null, null));
    }

    private static IngestionGraph.Node gateway(String id) {
        return new IngestionGraph.Node(id, IngestionGraph.KIND_CONDITION, 400.0, 0.0,
                new IngestionGraph.NodeData(null, null, null, "分支"));
    }

    private static IngestionGraph.Edge edge(String id, String source, String target) {
        return new IngestionGraph.Edge(id, source, target, null, null);
    }

    private static IngestionGraph.Edge branch(String id, String source, String target, String json) {
        try {
            return new IngestionGraph.Edge(id, source, target, null, MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static IngestionGraph graph(List<IngestionGraph.Node> nodes, List<IngestionGraph.Edge> edges) {
        return new IngestionGraph(1, nodes, edges);
    }

    // ==================== 校验 ====================

    @Test
    void validLinearGraphPasses() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), processor("n2", "parser"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "n2"), edge("e3", "n2", "end")));
        assertFalse(IngestionGraphValidator.validate(g).hasError());
    }

    @Test
    void validBranchGraphPasses() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        branch("e3", "c1", "n2", "{\"field\":\"mimeType\",\"operator\":\"eq\",\"value\":\"application/pdf\"}"),
                        edge("e4", "c1", "n3"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        assertFalse(IngestionGraphValidator.validate(g).hasError());
    }

    @Test
    void rejectsInvalidNodeTypeAndMissingProcessor() {
        IngestionGraph badType = graph(
                List.of(start(), processor("n1", "unknown_node"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "end")));
        assertTrue(IngestionGraphValidator.validate(badType).hasError());

        IngestionGraph noProcessor = graph(
                List.of(start(), end()),
                List.of(edge("e1", "start", "end")));
        assertTrue(IngestionGraphValidator.validate(noProcessor).hasError());
    }

    @Test
    void rejectsCycle() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), processor("n2", "parser"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "n2"),
                        edge("e3", "n2", "n1"), edge("e4", "n2", "end")));
        assertTrue(IngestionGraphValidator.validate(g).hasError());
    }

    @Test
    void rejectsGatewayWithMultipleUnconditionalEdges() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        edge("e3", "c1", "n2"), edge("e4", "c1", "n3"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        IngestionGraphValidator.ValidationResult result = IngestionGraphValidator.validate(g);
        assertTrue(result.hasError());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("无条件")), result.errors().toString());
    }

    @Test
    void rejectsProcessorMultipleUnconditionalEdges() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), processor("n2", "parser"),
                        processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "n2"), edge("e3", "n1", "n3"),
                        edge("e4", "n2", "end"), edge("e5", "n3", "end")));
        assertTrue(IngestionGraphValidator.validate(g).hasError());
    }

    // ==================== 编译 ====================

    @Test
    void compileLinearGraph() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), processor("n2", "parser"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "n2"), edge("e3", "n2", "end")));
        List<NodeConfig> configs = IngestionGraphCompiler.compile(g);
        assertEquals(2, configs.size());
        assertEquals("n1", configs.get(0).getNodeId());
        assertEquals("fetcher", configs.get(0).getNodeType());
        assertEquals("n2", configs.get(0).getNextNodeId());
        assertNull(configs.get(0).getBranches());
        assertEquals("n2", configs.get(1).getNodeId());
        assertNull(configs.get(1).getNextNodeId()); // 结束节点不产出配置
    }

    @Test
    void compileBranchGraphInlinesGateway() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        branch("e3", "c1", "n2", "{\"field\":\"mimeType\",\"operator\":\"eq\",\"value\":\"application/pdf\"}"),
                        edge("e4", "c1", "n3"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        List<NodeConfig> configs = IngestionGraphCompiler.compile(g);
        // 条件网关不产出运行节点
        assertEquals(3, configs.size());
        NodeConfig fetcher = configs.get(0);
        assertEquals("n1", fetcher.getNodeId());
        assertNotNull(fetcher.getBranches());
        assertEquals(1, fetcher.getBranches().size());
        assertEquals("n2", fetcher.getBranches().get(0).getNextNodeId());
        // 兜底：c1 的无条件出边指向 n3
        assertEquals("n3", fetcher.getNextNodeId());
    }

    @Test
    void compileBranchWithoutFallback() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        branch("e3", "c1", "n2", "{\"field\":\"mimeType\",\"value\":\"a\"}"),
                        branch("e4", "c1", "n3", "{\"field\":\"mimeType\",\"value\":\"b\"}"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        List<NodeConfig> configs = IngestionGraphCompiler.compile(g);
        NodeConfig fetcher = configs.get(0);
        assertEquals(2, fetcher.getBranches().size());
        assertNull(fetcher.getNextNodeId()); // 无条件兜底时，全部不命中则结束
    }

    @Test
    void compileRejectsEmptyGraph() {
        assertThrows(com.byteq.ai.ragstudio.framework.exception.ClientException.class,
                () -> IngestionGraphCompiler.compile(null));
    }

    // ==================== 旧数据兼容（节点 → 图） ====================

    @Test
    void factoryBuildsLinearGraphFromNodes() {
        List<NodeConfig> nodes = List.of(
                NodeConfig.builder().nodeId("step_1").nodeType("fetcher").nextNodeId("step_2").build(),
                NodeConfig.builder().nodeId("step_2").nodeType("parser").nextNodeId("step_3").build(),
                NodeConfig.builder().nodeId("step_3").nodeType("indexer").build());
        IngestionGraph graph = IngestionGraphFactory.fromNodes(nodes);

        assertEquals(5, graph.nodeCount()); // start + 3 + end
        assertFalse(IngestionGraphValidator.validate(graph).hasError());
        assertEquals(IngestionGraph.KIND_START, graph.nodes().get(0).kind());
        assertEquals(IngestionGraph.KIND_END, graph.nodes().get(4).kind());

        // 往返：图 → 编译 → 与原节点链一致
        List<NodeConfig> compiled = IngestionGraphCompiler.compile(graph);
        assertEquals(3, compiled.size());
        assertEquals("step_1", compiled.get(0).getNodeId());
        assertEquals("fetcher", compiled.get(0).getNodeType());
        assertEquals("step_2", compiled.get(0).getNextNodeId());
        assertEquals("step_3", compiled.get(1).getNextNodeId());
        assertNull(compiled.get(2).getNextNodeId());
    }

    @Test
    void factoryHandlesBranchNodes() {
        List<NodeConfig.Branch> branches = new ArrayList<>();
        branches.add(NodeConfig.Branch.builder()
                .condition(MAPPER.valueToTree(Map.of("field", "mimeType", "value", "text/markdown")))
                .nextNodeId("step_3").build());
        List<NodeConfig> nodes = List.of(
                NodeConfig.builder().nodeId("step_1").nodeType("fetcher").nextNodeId("step_2").build(),
                NodeConfig.builder().nodeId("step_2").nodeType("parser")
                        .nextNodeId("step_4").branches(branches).build(),
                NodeConfig.builder().nodeId("step_3").nodeType("enhancer").nextNodeId("step_4").build(),
                NodeConfig.builder().nodeId("step_4").nodeType("indexer").build());
        IngestionGraph graph = IngestionGraphFactory.fromNodes(nodes);
        assertFalse(IngestionGraphValidator.validate(graph).hasError(),
                IngestionGraphValidator.validate(graph).errors().toString());
        // 分支节点应生成条件网关
        assertTrue(graph.nodes().stream().anyMatch(n -> IngestionGraph.KIND_CONDITION.equals(n.kind())));
        // 往返编译后分支保留
        List<NodeConfig> compiled = IngestionGraphCompiler.compile(graph);
        NodeConfig parser = compiled.stream().filter(c -> "step_2".equals(c.getNodeId())).findFirst().orElseThrow();
        assertNotNull(parser.getBranches());
        assertEquals("step_3", parser.getBranches().get(0).getNextNodeId());
        assertEquals("step_4", parser.getNextNodeId());
    }

    // ==================== 引擎排他分支执行 ====================

    /** 记录执行轨迹的假节点 */
    private static final class RecordingNode implements IngestionNode {
        private final String type;
        private final List<String> trace;

        RecordingNode(String type, List<String> trace) {
            this.type = type;
            this.trace = trace;
        }

        @Override
        public String getNodeType() {
            return type;
        }

        @Override
        public NodeResult execute(IngestionContext context, NodeConfig config) {
            trace.add(config.getNodeId());
            return NodeResult.ok("done:" + config.getNodeId());
        }
    }

    private static IngestionEngine engineOf(List<String> trace) {
        return new IngestionEngine(
                List.of(new RecordingNode("fetcher", trace), new RecordingNode("parser", trace),
                        new RecordingNode("indexer", trace), new RecordingNode("enhancer", trace)),
                new ConditionEvaluator(MAPPER),
                new NodeOutputExtractor());
    }

    @Test
    void engineTakesConditionalBranch() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        branch("e3", "c1", "n2", "{\"field\":\"mimeType\",\"operator\":\"eq\",\"value\":\"application/pdf\"}"),
                        edge("e4", "c1", "n3"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        List<NodeConfig> configs = IngestionGraphCompiler.compile(g);

        List<String> trace = new ArrayList<>();
        IngestionContext context = IngestionContext.builder()
                .mimeType("application/pdf")
                .source(DocumentSource.builder().type(SourceType.FILE).fileName("a.pdf").build())
                .build();
        engineOf(trace).execute(com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition.builder()
                .id("p1").name("t").nodes(configs).build(), context);

        assertEquals(List.of("n1", "n2"), trace, "PDF 应走 parser 分支");
    }

    @Test
    void engineTakesFallbackWhenNoConditionMatches() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        branch("e3", "c1", "n2", "{\"field\":\"mimeType\",\"operator\":\"eq\",\"value\":\"application/pdf\"}"),
                        edge("e4", "c1", "n3"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        List<NodeConfig> configs = IngestionGraphCompiler.compile(g);

        List<String> trace = new ArrayList<>();
        IngestionContext context = IngestionContext.builder().mimeType("text/plain").build();
        engineOf(trace).execute(com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition.builder()
                .id("p1").name("t").nodes(configs).build(), context);

        assertEquals(List.of("n1", "n3"), trace, "非 PDF 应走兜底分支");
    }

    @Test
    void engineSupportsLegacyOpAliasAndFieldMapping() {
        // 旧前端产物：op 别名 + source_type 扁平字段
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        branch("e3", "c1", "n2", "{\"field\":\"source_type\",\"op\":\"eq\",\"value\":\"file\"}"),
                        edge("e4", "c1", "n3"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        List<NodeConfig> configs = IngestionGraphCompiler.compile(g);

        List<String> trace = new ArrayList<>();
        IngestionContext context = IngestionContext.builder()
                .source(DocumentSource.builder().type(SourceType.FILE).build())
                .build();
        engineOf(trace).execute(com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition.builder()
                .id("p1").name("t").nodes(configs).build(), context);

        assertEquals(List.of("n1", "n2"), trace, "旧协议 op + source_type 应命中 file 分支");
    }

    @Test
    void engineSupportsExprCondition() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "fetcher"), gateway("c1"),
                        processor("n2", "parser"), processor("n3", "indexer"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "c1"),
                        branch("e3", "c1", "n2", "{\"expr\":\"mimeType == 'text/markdown'\"}"),
                        edge("e4", "c1", "n3"),
                        edge("e5", "n2", "end"), edge("e6", "n3", "end")));
        List<NodeConfig> configs = IngestionGraphCompiler.compile(g);

        List<String> trace = new ArrayList<>();
        IngestionContext context = IngestionContext.builder().mimeType("text/markdown").build();
        engineOf(trace).execute(com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition.builder()
                .id("p1").name("t").nodes(configs).build(), context);

        assertEquals(List.of("n1", "n2"), trace, "expr 表达式应命中 markdown 分支");
    }

    // ==================== JSON 编解码 ====================

    @Test
    void graphJsonRoundTrip() {
        IngestionGraph g = graph(
                List.of(start(), processor("n1", "chunker"), end()),
                List.of(edge("e1", "start", "n1"), edge("e2", "n1", "end")));
        String json = IngestionGraphJson.toJson(g);
        assertNotNull(json);
        IngestionGraph parsed = IngestionGraphJson.parse(json);
        assertNotNull(parsed);
        assertEquals(3, parsed.nodeCount());
        assertEquals("chunker", parsed.nodes().get(1).data().nodeType());
        assertEquals(2, parsed.edges().size());
    }
}
