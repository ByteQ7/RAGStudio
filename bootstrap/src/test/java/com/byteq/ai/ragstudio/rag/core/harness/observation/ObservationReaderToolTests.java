package com.byteq.ai.ragstudio.rag.core.harness.observation;

import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观察回读工具测试：全文/区间/关键词回读、未知句柄提示、返回长度限流。
 */
class ObservationReaderToolTests {

    private static final String TEXT = "第一行 年假规则说明\n"
            + "第二行 入职满一年享受5天年假\n"
            + "第三行 " + "填充".repeat(600) + "\n"
            + "最后一行 结束标记 END_MARKER";

    private ObservationStore store;
    private ObservationReaderTool tool;

    @BeforeEach
    void setUp() {
        ObservationMaskProperties props = new ObservationMaskProperties();
        store = new ObservationStore(props);
        store.register("call-1", "rag_search", Map.of("query", "年假"), TEXT, 0, true);
        tool = new ObservationReaderTool(store, props);
    }

    @Test
    void fullModeReturnsContentAndTruncationHint() {
        ToolResult result = tool.execute(Map.of("handle", "obs_1", "max_chars", 100));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("观察 obs_1"));
        assertTrue(result.getContent().contains("第一行 年假规则说明"));
        assertTrue(result.getContent().contains("剩余"));
        assertTrue(result.getContent().contains("offset=100"));
        assertEquals(1, store.readerCalls());
    }

    @Test
    void rangeModeReadsExactWindow() {
        ToolResult result = tool.execute(Map.of(
                "handle", "obs_1", "mode", "range", "offset", 0, "limit", 12));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("区间回读"));
        assertTrue(result.getContent().contains("第一行 年假规则说明"));
        assertFalse(result.getContent().contains("END_MARKER"));
    }

    @Test
    void grepModeFindsMatchingLines() {
        ToolResult result = tool.execute(Map.of(
                "handle", "obs_1", "mode", "grep", "query", "年假"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("匹配 2 行"));
        assertTrue(result.getContent().contains("1: 第一行 年假规则说明"));
        assertTrue(result.getContent().contains("2: 第二行 入职满一年享受5天年假"));
    }

    @Test
    void grepWithoutMatchExplainsHowToContinue() {
        ToolResult result = tool.execute(Map.of(
                "handle", "obs_1", "mode", "grep", "query", "不存在的关键词"));
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("未找到"));
        assertTrue(result.getContent().contains("mode=\"full\""));
    }

    @Test
    void unknownHandleListsAvailableHandles() {
        ToolResult result = tool.execute(Map.of("handle", "obs_9"));
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("句柄不存在"));
        assertTrue(result.getContent().contains("obs_1"));
        assertEquals(0, store.readerCalls());
    }

    @Test
    void missingHandleRejected() {
        ToolResult result = tool.execute(Map.of());
        assertFalse(result.isSuccess());
        assertTrue(result.getContent().contains("handle"));
    }
}
