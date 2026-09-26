package com.byteq.ai.ragstudio.rag.core.harness.observation;

import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.ModelCallInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观察掩码中间件测试：压缩旧观察、保留最近一轮、保留 ToolResultBlock 协议字段、
 * 关闭开关时透传、结论未就绪时透传。
 */
class ObservationMaskMiddlewareTests {

    private static final String LONG_TEXT = "FULL_START_" + "内容".repeat(400) + "_FULL_END";

    private ObservationEntry ready(ObservationStore store, String callId, int iteration) {
        ObservationEntry entry = store.register(callId, "rag_search",
                Map.of("query", "年假"), LONG_TEXT, iteration, true);
        assertNotNull(entry);
        entry.markReady("年假结论 [^chunk_1]");
        return entry;
    }

    private Msg toolMsg(String callId, String text) {
        ToolResultBlock block = ToolResultBlock.of(callId, "rag_search",
                TextBlock.builder().text(text).build());
        return Msg.builder().name("qa").role(MsgRole.TOOL).content(block).build();
    }

    private Msg userMsg(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build();
    }

    private List<Msg> invoke(ObservationMaskMiddleware middleware, List<Msg> messages) {
        AtomicReference<List<Msg>> captured = new AtomicReference<>();
        middleware.onModelCall(null, null,
                new ModelCallInput(messages, List.of(), null, null), input -> {
                    captured.set(input.messages());
                    return Flux.<AgentEvent>empty();
                });
        assertNotNull(captured.get(), "next 必须被调用");
        return captured.get();
    }

    private String textOf(Msg msg) {
        StringBuilder sb = new StringBuilder();
        ToolResultBlock toolBlock = msg.getFirstContentBlock(ToolResultBlock.class);
        if (toolBlock != null) {
            for (ContentBlock block : toolBlock.getOutput()) {
                if (block instanceof TextBlock text && text.getText() != null) {
                    sb.append(text.getText());
                }
            }
            return sb.toString();
        }
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    @Test
    void masksOlderObservationAndKeepsLatest() {
        ObservationMaskProperties props = new ObservationMaskProperties();
        ObservationStore store = new ObservationStore(props);
        ready(store, "call-1", 0);
        ready(store, "call-2", 1);
        ObservationMaskMiddleware middleware = new ObservationMaskMiddleware(store, props);

        List<Msg> messages = new ArrayList<>(List.of(
                userMsg("问题"), toolMsg("call-1", LONG_TEXT), toolMsg("call-2", LONG_TEXT)));
        List<Msg> result = invoke(middleware, messages);

        String first = textOf(result.get(1));
        String second = textOf(result.get(2));
        assertTrue(first.contains("[观察已压缩 obs_1]"));
        assertTrue(first.contains("年假结论 [^chunk_1]"));
        assertFalse(first.contains("FULL_START_"));
        assertEquals(LONG_TEXT, second, "最近一轮观察保持全文");

        // 协议字段必须保留：OpenAI 兼容层用 ToolResultBlock.id 作为 tool_call_id
        ToolResultBlock maskedBlock = result.get(1).getFirstContentBlock(ToolResultBlock.class);
        assertNotNull(maskedBlock);
        assertEquals("call-1", maskedBlock.getId());
        assertEquals("rag_search", maskedBlock.getName());
        assertSame(MsgRole.TOOL, result.get(1).getRole());
    }

    @Test
    void passesThroughWhenDisabled() {
        ObservationMaskProperties props = new ObservationMaskProperties();
        props.setEnabled(false);
        ObservationStore store = new ObservationStore(props);
        ready(store, "call-1", 0);
        ready(store, "call-2", 1);
        ObservationMaskMiddleware middleware = new ObservationMaskMiddleware(store, props);

        List<Msg> messages = List.of(toolMsg("call-1", LONG_TEXT), toolMsg("call-2", LONG_TEXT));
        List<Msg> result = invoke(middleware, messages);
        assertEquals(LONG_TEXT, textOf(result.get(0)));
    }

    @Test
    void passesThroughWhenConclusionPending() {
        ObservationMaskProperties props = new ObservationMaskProperties();
        ObservationStore store = new ObservationStore(props);
        store.register("call-1", "rag_search", Map.of(), LONG_TEXT, 0, true);
        ready(store, "call-2", 1);
        ObservationMaskMiddleware middleware = new ObservationMaskMiddleware(store, props);

        List<Msg> messages = List.of(toolMsg("call-1", LONG_TEXT), toolMsg("call-2", LONG_TEXT));
        List<Msg> result = invoke(middleware, messages);
        assertEquals(LONG_TEXT, textOf(result.get(0)), "结论未就绪时保持原文");
    }

    @Test
    void leavesUnknownToolMessagesUntouched() {
        ObservationMaskProperties props = new ObservationMaskProperties();
        ObservationStore store = new ObservationStore(props);
        ObservationMaskMiddleware middleware = new ObservationMaskMiddleware(store, props);

        List<Msg> messages = List.of(toolMsg("unknown-call", LONG_TEXT));
        List<Msg> result = invoke(middleware, messages);
        assertSame(messages.get(0), result.get(0), "未登记的工具消息不应被重建");
    }
}
