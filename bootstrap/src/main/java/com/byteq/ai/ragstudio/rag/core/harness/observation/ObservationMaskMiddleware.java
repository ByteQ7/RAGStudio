package com.byteq.ai.ragstudio.rag.core.harness.observation;

import cn.hutool.core.collection.CollUtil;
import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 观察掩码中间件（Observation Mask）
 * <p>
 * 在每次模型调用前（{@code onModelCall}）把上下文中的旧工具结果替换为
 * 「结论 + 句柄」的压缩文本，完整原文保留在 {@link ObservationStore} 中，
 * 由 {@link ObservationReaderTool} 支持按需回读。关键性质：
 * <ul>
 *   <li><b>只改模型输入</b>：不写回 AgentState 记忆，不影响 agentSteps / SSE 展示；</li>
 *   <li><b>覆盖全部模型调用</b>：常规 ReAct 推理与 maxIters 框架汇总都走
 *       {@code onModelCall}，避免汇总时把未压缩的全量上下文再次送入模型；</li>
 *   <li><b>必须保留 ToolResultBlock id</b>：OpenAI 兼容协议用其作为 tool_call_id，
 *       换成纯文本消息会导致厂商 400；</li>
 *   <li><b>最近一轮不压缩</b>：保证 Agent 至少完整读过一次结果（keepRecentRounds）；</li>
 *   <li><b>结论未就绪不压缩</b>：PENDING 阶段保持原文，避免把"生成中"的占位喂给模型；</li>
 *   <li><b>冻结式掩码</b>：首次压缩后的文本不再变化，保持多轮请求前缀稳定。</li>
 * </ul>
 */
@Slf4j
public class ObservationMaskMiddleware implements MiddlewareBase {

    private final ObservationStore store;
    private final ObservationMaskProperties properties;

    public ObservationMaskMiddleware(ObservationStore store, ObservationMaskProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (store == null || !Boolean.TRUE.equals(properties.getEnabled())
                || input == null || CollUtil.isEmpty(input.messages())) {
            return next.apply(input);
        }
        List<Msg> messages = input.messages();
        List<Msg> rebuilt = null;
        int beforeChars = 0;
        int afterChars = 0;
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            if (msg == null || msg.getRole() == null
                    || (msg.getRole() != MsgRole.TOOL && msg.getRole() != MsgRole.SYSTEM)) {
                continue;
            }
            ToolResultBlock block = msg.getFirstContentBlock(ToolResultBlock.class);
            if (block == null || block.getId() == null) {
                continue;
            }
            ObservationEntry entry = store.findByToolCallId(block.getId());
            if (entry == null) {
                continue;
            }
            String masked = store.maskedText(entry);
            if (masked == null) {
                continue;
            }
            if (rebuilt == null) {
                rebuilt = new ArrayList<>(messages);
            }
            beforeChars += textLength(block);
            rebuilt.set(i, rebuildToolMessage(msg, block, masked));
            afterChars += masked.length();
        }
        if (rebuilt == null) {
            return next.apply(input);
        }
        log.debug("观察掩码生效: 本次模型调用压缩 {} 条工具结果, 字符 {} -> {}",
                store.maskedObservations(), beforeChars, afterChars);
        return next.apply(new ModelCallInput(rebuilt, input.tools(), input.options(), input.model()));
    }

    /** 重建工具消息：保留 ToolResultBlock 的 id/name/state/metadata，只替换文本输出 */
    private Msg rebuildToolMessage(Msg msg, ToolResultBlock block, String maskedText) {
        List<ContentBlock> output = new ArrayList<>();
        output.add(TextBlock.builder().text(maskedText).build());
        for (ContentBlock content : block.getOutput()) {
            if (!(content instanceof TextBlock)) {
                output.add(content);
            }
        }
        ToolResultBlock maskedBlock = new ToolResultBlock(
                block.getId(), block.getName(), output, block.getMetadata(), block.getState());
        return Msg.builder()
                .id(msg.getId())
                .name(msg.getName())
                .role(msg.getRole())
                .content(maskedBlock)
                .build();
    }

    private int textLength(ToolResultBlock block) {
        int len = 0;
        for (ContentBlock content : block.getOutput()) {
            if (content instanceof TextBlock text && text.getText() != null) {
                len += text.getText().length();
            }
        }
        return len;
    }
}
