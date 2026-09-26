package com.byteq.ai.ragstudio.rag.core.harness.observation;

import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import com.byteq.ai.ragstudio.rag.core.tool.ToolResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观察掩码在真实 ReActAgent 循环中的行为验证
 * <p>
 * 用假模型驱动三轮迭代：第 1/2 轮各产生一次工具调用，第 3 轮产出最终回答。
 * 断言：最近一轮工具结果保持全文；更早的旧观察被压缩为「结论 + 句柄」且
 * ToolResultBlock.id 保留；Agent 可凭句柄通过 observation_reader 回读全文。
 */
class ObservationMaskIntegrationTest {

    private static final String FULL_TEXT = "FULL_START_" + "年假规则正文".repeat(120) + "_FULL_END";

    /** 假模型：stopAt 轮之前返回工具调用，之后返回最终回答；逐轮记录各工具结果消息文本 */
    private static final class RecordingModel implements Model {
        private final int stopAt;
        private final List<Map<String, String>> toolTextsByRound = new ArrayList<>();
        private final AtomicInteger call = new AtomicInteger();

        RecordingModel(int stopAt) {
            this.stopAt = stopAt;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            Map<String, String> texts = new HashMap<>();
            for (Msg msg : messages) {
                ToolResultBlock block = msg.getFirstContentBlock(ToolResultBlock.class);
                if (block != null && block.getId() != null) {
                    texts.put(block.getId(), textOf(block));
                }
            }
            toolTextsByRound.add(texts);

            int round = call.incrementAndGet();
            ChatResponse response;
            if (round < stopAt) {
                response = ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder()
                                .id("call-" + round)
                                .name("fake_tool")
                                .input(Map.of("query", "年假"))
                                .build()))
                        .finishReason("tool_calls")
                        .build();
            } else {
                response = ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("最终回答：完成").build()))
                        .finishReason("stop")
                        .build();
            }
            return Flux.just(response);
        }

        @Override
        public String getModelName() {
            return "recording-model";
        }
    }

    private static String textOf(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        for (var content : block.getOutput()) {
            if (content instanceof TextBlock text && text.getText() != null) {
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    /** 模拟执行器的观察登记：工具执行时按轮次登记并同步标记结论就绪 */
    private void registerFakeTool(ReActAgent agent, ObservationStore store) {
        AtomicInteger toolRound = new AtomicInteger();
        agent.getToolkit().registerTool(new AgentTool() {
            @Override
            public String getName() {
                return "fake_tool";
            }

            @Override
            public String getDescription() {
                return "测试工具";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                String callId = param.getToolUseBlock().getId();
                int iteration = toolRound.getAndIncrement();
                ObservationEntry entry = store.register(callId, "fake_tool",
                        Map.of("query", "年假"), FULL_TEXT, iteration, true);
                assertNotNull(entry, "长结果应被登记");
                entry.markReady("结论_" + callId + " [^chunk_1]");
                return Mono.just(ToolResultBlock.text(
                        "Observation: 工具 [fake_tool] 执行成功:\n" + FULL_TEXT));
            }
        });
    }

    @Test
    void olderObservationMaskedAndRestorableThroughReader() {
        ObservationMaskProperties props = new ObservationMaskProperties();
        ObservationStore store = new ObservationStore(props);
        RecordingModel model = new RecordingModel(3);

        ReActAgent agent = ReActAgent.builder()
                .name("qa")
                .sysPrompt("BASE_PROMPT")
                .model(model)
                .maxIters(3)
                .middleware(new ObservationMaskMiddleware(store, props))
                .build();
        registerFakeTool(agent, store);

        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("年假有几天？").build()).build();
        agent.streamEvents(List.of(user), RuntimeContext.builder().sessionId("t1").build())
                .blockLast();

        assertEquals(3, model.toolTextsByRound.size(), "应经历三轮模型调用");
        assertTrue(model.toolTextsByRound.get(1).get("call-1").contains("FULL_START_"),
                "第 2 轮：Agent 必须完整读过一次最新工具结果");

        Map<String, String> thirdRound = model.toolTextsByRound.get(2);
        String masked = thirdRound.get("call-1");
        assertNotNull(masked, "ToolResultBlock.id 必须保留（OpenAI tool_call_id 协议）");
        assertTrue(masked.contains("[观察已压缩 obs_1]"));
        assertTrue(masked.contains("结论_call-1 [^chunk_1]"));
        assertFalse(masked.contains("FULL_START_"), "旧观察正文应从模型输入中移除");
        assertTrue(masked.contains("observation_reader(handle=\"obs_1\")"));
        assertTrue(thirdRound.get("call-2").contains("FULL_START_"), "最近一轮结果保持全文");

        // 凭句柄回读完整原文
        ObservationReaderTool reader = new ObservationReaderTool(store, props);
        ToolResult read = reader.execute(Map.of("handle", "obs_1", "mode", "grep", "query", "FULL_START_"));
        assertTrue(read.isSuccess());
        assertTrue(read.getContent().contains("FULL_START_"));
        assertEquals(1, store.readerCalls());
    }

    @Test
    void maxItersSummaryCallAlsoSeesMaskedContext() {
        ObservationMaskProperties props = new ObservationMaskProperties();
        ObservationStore store = new ObservationStore(props);
        // 永不收敛：maxIters=2 触发框架 summarizing()，第三次模型调用即汇总调用
        RecordingModel model = new RecordingModel(Integer.MAX_VALUE);

        ReActAgent agent = ReActAgent.builder()
                .name("qa")
                .sysPrompt("BASE_PROMPT")
                .model(model)
                .maxIters(2)
                .middleware(new ObservationMaskMiddleware(store, props))
                .build();
        registerFakeTool(agent, store);

        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("年假有几天？").build()).build();
        agent.streamEvents(List.of(user), RuntimeContext.builder().sessionId("t2").build())
                .blockLast();

        assertEquals(3, model.toolTextsByRound.size(), "两轮推理 + 一次汇总模型调用");
        Map<String, String> summaryRound = model.toolTextsByRound.get(2);
        String masked = summaryRound.get("call-1");
        assertNotNull(masked, "汇总调用也必须经过观察掩码");
        assertTrue(masked.contains("[观察已压缩 obs_1]"));
        assertFalse(masked.contains("FULL_START_"), "汇总输入不应再携带全量旧观察");
        assertTrue(summaryRound.get("call-2").contains("FULL_START_"), "最近一轮仍保持全文");
    }
}
