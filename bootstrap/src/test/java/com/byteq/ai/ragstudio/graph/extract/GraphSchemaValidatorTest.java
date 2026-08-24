package com.byteq.ai.ragstudio.graph.extract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphSchemaValidator} 单元测试
 * <p>
 * 覆盖「合法 JSON 但抽取为空」的语义：应视为成功的空抽取而非失败，
 * 避免纯数值/表格类 chunk 每次重建都重试并永久标记 FAILED。
 * </p>
 */
class GraphSchemaValidatorTest {

    @Test
    void 合法JSON且抽取为空应返回空结果而非null() {
        GraphExtractionResult result = GraphSchemaValidator.parse(
                "{\"entities\":[],\"relations\":[]}", 30, 50);
        assertNotNull(result);
        assertTrue(result.entities().isEmpty());
        assertTrue(result.relations().isEmpty());
    }

    @Test
    void 非法输出应返回null() {
        assertNull(GraphSchemaValidator.parse(null, 30, 50));
        assertNull(GraphSchemaValidator.parse("", 30, 50));
        assertNull(GraphSchemaValidator.parse("not json", 30, 50));
        assertNull(GraphSchemaValidator.parse("[1,2,3]", 30, 50));
        assertNull(GraphSchemaValidator.parse("```json\n{bad}\n```", 30, 50));
    }

    @Test
    void 正常抽取应解析实体与关系() {
        String raw = """
                {"entities":[{"name":"财务部","type":"DEPT","description":"负责财务审核"}],
                 "relations":[{"source":"财务部","target":"报销单","predicate":"审核","evidence":"财务部审核报销单"}]}
                """;
        GraphExtractionResult result = GraphSchemaValidator.parse(raw, 30, 50);
        assertNotNull(result);
        assertEquals(1, result.entities().size());
        assertEquals("财务部", result.entities().get(0).name());
        assertEquals(1, result.relations().size());
        assertEquals("审核", result.relations().get(0).predicate());
    }

    @Test
    void markdown代码块包裹的JSON应能解析() {
        String raw = "```json\n{\"entities\":[],\"relations\":[]}\n```";
        GraphExtractionResult result = GraphSchemaValidator.parse(raw, 30, 50);
        assertNotNull(result);
        assertTrue(result.entities().isEmpty());
    }

    @Test
    void 仅实体为空但关系非空应正常解析() {
        String raw = "{\"entities\":[],\"relations\":[{\"source\":\"A\",\"target\":\"B\",\"predicate\":\"负责\",\"evidence\":\"\"}]}";
        GraphExtractionResult result = GraphSchemaValidator.parse(raw, 30, 50);
        assertNotNull(result);
        assertEquals(1, result.relations().size());
    }
}