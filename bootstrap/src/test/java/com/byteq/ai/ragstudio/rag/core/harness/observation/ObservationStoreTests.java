package com.byteq.ai.ragstudio.rag.core.harness.observation;

import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观察存储测试：登记过滤、最近轮次保留、结论就绪判定、掩码冻结与容量上限。
 */
class ObservationStoreTests {

    private static final String LONG_TEXT = "FULL_START_" + "内容".repeat(400) + "_FULL_END";

    private ObservationMaskProperties properties() {
        return new ObservationMaskProperties();
    }

    private ObservationEntry readyEntry(ObservationStore store, String callId, int iteration) {
        ObservationEntry entry = store.register(callId, "rag_search",
                Map.of("query", "年假 休假"), LONG_TEXT, iteration, true);
        assertNotNull(entry);
        entry.markReady("年假规则：入职满1年5天 [^chunk_1]");
        return entry;
    }

    @Test
    void keepLatestRoundAndMaskOlderWithConclusion() {
        ObservationMaskProperties props = properties();
        ObservationStore store = new ObservationStore(props);

        ObservationEntry first = readyEntry(store, "call-1", 0);
        assertNull(store.maskedText(first), "最新一轮的观察必须保留全文");

        ObservationEntry second = readyEntry(store, "call-2", 1);
        String masked = store.maskedText(first);
        assertNotNull(masked, "上一轮观察应被压缩");
        assertTrue(masked.contains("[观察已压缩 obs_1]"));
        assertTrue(masked.contains("工具 rag_search"));
        assertTrue(masked.contains("年假规则：入职满1年5天 [^chunk_1]"));
        assertTrue(masked.contains("observation_reader(handle=\"obs_1\")"));
        assertTrue(masked.contains(String.valueOf(LONG_TEXT.length())));
        assertNull(store.maskedText(second), "最新观察不压缩");

        assertEquals(1, store.maskedObservations());
        assertTrue(store.savedChars() > 0);
    }

    @Test
    void pendingConclusionStaysUnmasked() {
        ObservationStore store = new ObservationStore(properties());
        ObservationEntry first = store.register("call-1", "rag_search", Map.of(), LONG_TEXT, 0, true);
        ObservationEntry second = store.register("call-2", "rag_search", Map.of(), LONG_TEXT, 1, true);
        assertNotNull(first);
        assertNotNull(second);
        second.markReady("结论");

        assertNull(store.maskedText(first), "结论未就绪时保持原文，避免喂给模型占位内容");
    }

    @Test
    void digestFallbackUsedWhenExtractionFails() {
        ObservationStore store = new ObservationStore(properties());
        ObservationEntry first = store.register("call-1", "rag_search", Map.of(), LONG_TEXT, 0, true);
        assertNotNull(first);
        first.markDigest();
        readyEntry(store, "call-2", 1);

        String masked = store.maskedText(first);
        assertNotNull(masked);
        assertTrue(masked.contains("头尾摘要"));
        assertTrue(masked.contains("FULL_START_"));
        assertTrue(masked.contains("_FULL_END"));
        assertTrue(masked.contains("中间省略"));
    }

    @Test
    void registerFiltersShortFailedAndExcludedResults() {
        ObservationMaskProperties props = properties();
        props.setExcludeTools(List.of("time_now"));
        ObservationStore store = new ObservationStore(props);

        assertNull(store.register("call-short", "rag_search", Map.of(), "短结果", 0, true));
        assertNull(store.register("call-error", "rag_search", Map.of(), LONG_TEXT, 0, false));
        assertNull(store.register("call-excluded", "time_now", Map.of(), LONG_TEXT, 0, true));

        props.setMaskErrors(true);
        assertNotNull(store.register("call-error-2", "rag_search", Map.of(), LONG_TEXT, 0, false));
    }

    @Test
    void registerIsIdempotentAndCapacityBounded() {
        ObservationMaskProperties props = properties();
        props.setMaxObservations(1);
        ObservationStore store = new ObservationStore(props);

        ObservationEntry first = store.register("call-1", "rag_search", Map.of(), LONG_TEXT, 0, true);
        assertNotNull(first);
        assertSame(first, store.register("call-1", "rag_search", Map.of(), LONG_TEXT, 0, true));
        assertNull(store.register("call-2", "rag_search", Map.of(), LONG_TEXT, 1, true));
        assertEquals(1, store.size());
    }

    @Test
    void maskedTextFrozenAfterFirstMask() {
        ObservationStore store = new ObservationStore(properties());
        ObservationEntry first = store.register("call-1", "rag_search", Map.of(), LONG_TEXT, 0, true);
        assertNotNull(first);
        first.markReady("结论A");
        readyEntry(store, "call-2", 1);

        String firstMask = store.maskedText(first);
        assertNotNull(firstMask);
        assertSame(firstMask, store.maskedText(first), "掩码文本冻结，保证多轮请求前缀稳定");
        assertNotNull(first.getMaskedText());
    }

    @Test
    void availableHandlesListed() {
        ObservationStore store = new ObservationStore(properties());
        readyEntry(store, "call-1", 0);
        readyEntry(store, "call-2", 1);

        List<String> handles = store.availableHandles();
        assertEquals(2, handles.size());
        assertTrue(handles.get(0).startsWith("obs_1"));
        assertTrue(handles.get(1).startsWith("obs_2"));
        assertNotNull(store.findByHandle("obs_1"));
        assertNull(store.findByHandle("obs_99"));
    }
}
