package com.byteq.ai.ragstudio.rag.core.harness.context;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态上下文中间件在真实 ReActAgent 循环中的行为验证
 * <p>
 * 用假模型驱动两轮迭代：第 1 轮输出 tool_call 并在工具执行中失效候选块，
 * 第 2 轮断言模型实际收到的 system message 中候选已消失（"用了就清除"）。
 */
class DynamicContextMiddlewareIntegrationTest {

    /** 假模型：第 1 轮返回工具调用，第 2 轮返回最终回答；记录每次请求的 system message */
    private static final class RecordingModel implements Model {
        private final List<String> systemPrompts = new ArrayList<>();
        private final AtomicInteger call = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            for (Msg msg : messages) {
                if (msg.getRole() == MsgRole.SYSTEM) {
                    systemPrompts.add(msg.getTextContent());
                }
            }
            int round = call.incrementAndGet();
            ChatResponse response;
            if (round == 1) {
                response = ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder()
                                .id("call-1")
                                .name("fake_tool")
                                .input(java.util.Map.of())
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

    @Test
    void candidateBlockDroppedAfterDeactivationInRealLoop() {
        DynamicContextRegistry registry = new DynamicContextRegistry();
        registry.register(new ContextBlock(ContextBlockIds.WORKFLOW_CANDIDATES,
                "CANDIDATE_MARKER_候选清单", 10));

        RecordingModel model = new RecordingModel();
        ReActAgent agent = ReActAgent.builder()
                .name("qa")
                .sysPrompt("BASE_PROMPT")
                .model(model)
                .maxIters(3)
                .middleware(new DynamicContextMiddleware(registry))
                .build();

        // 工具执行时失效候选块（模拟 workflow_use）
        agent.getToolkit().registerTool(new io.agentscope.core.tool.AgentTool() {
            @Override
            public String getName() {
                return "fake_tool";
            }

            @Override
            public String getDescription() {
                return "测试工具";
            }

            @Override
            public java.util.Map<String, Object> getParameters() {
                return java.util.Map.of("type", "object", "properties", java.util.Map.of());
            }

            @Override
            public reactor.core.publisher.Mono<io.agentscope.core.message.ToolResultBlock> callAsync(
                    io.agentscope.core.tool.ToolCallParam param) {
                registry.deactivate(ContextBlockIds.WORKFLOW_CANDIDATES);
                return reactor.core.publisher.Mono.just(
                        io.agentscope.core.message.ToolResultBlock.text("工具执行完成"));
            }
        });

        Msg user = Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text("请处理").build()).build();
        agent.streamEvents(List.of(user), RuntimeContext.builder().sessionId("t1").build())
                .blockLast();

        assertTrue(model.systemPrompts.size() >= 2, "应至少经历两轮模型调用");
        assertTrue(model.systemPrompts.get(0).contains("BASE_PROMPT"));
        assertTrue(model.systemPrompts.get(0).contains("CANDIDATE_MARKER_候选清单"),
                "第 1 轮应注入候选清单");
        for (int i = 1; i < model.systemPrompts.size(); i++) {
            assertFalse(model.systemPrompts.get(i).contains("CANDIDATE_MARKER_候选清单"),
                    "第 " + (i + 1) + " 轮候选清单应已被清除");
            assertTrue(model.systemPrompts.get(i).contains("BASE_PROMPT"),
                    "基础提示词必须保留");
        }
    }
}
