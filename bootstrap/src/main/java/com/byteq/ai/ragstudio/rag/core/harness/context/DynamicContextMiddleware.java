package com.byteq.ai.ragstudio.rag.core.harness.context;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 动态上下文中间件（Harness · 上下文子模块）
 * <p>
 * 在每轮模型调用前（{@code onReasoning}）重建 system message：
 * 以 Agent 构建时的静态 system prompt 为基底，按 {@link DynamicContextRegistry}
 * 当前生效块拼装追加。关键性质：
 * <ul>
 *   <li><b>每轮重建</b>：工具在上一轮迭代中失效的块（如工作流候选清单）不会出现在下一轮；</li>
 *   <li><b>不污染记忆</b>：改写只作用于当次模型调用的输入消息，不写入 AgentState 记忆；</li>
 *   <li><b>与框架解耦</b>：AgentScope 升级时只需适配本中间件。</li>
 * </ul>
 * <p>
 * 注意：AgentScope 传入的 {@link ReasoningInput#messages()} 已由框架在头部拼好
 * system message（{@code prependSystemMsg}），因此这里只需定位并重建该条消息。
 */
@Slf4j
public class DynamicContextMiddleware implements MiddlewareBase {

    private final DynamicContextRegistry registry;

    public DynamicContextMiddleware(DynamicContextRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (registry == null || input == null || CollUtil.isEmpty(input.messages())) {
            return next.apply(input);
        }
        List<ContextBlock> active = registry.activeBlocks();
        if (CollUtil.isEmpty(active)) {
            return next.apply(input);
        }

        List<Msg> messages = new ArrayList<>(input.messages());
        int systemIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) != null && messages.get(i).getRole() == MsgRole.SYSTEM) {
                systemIndex = i;
                break;
            }
        }
        if (systemIndex < 0) {
            log.debug("动态上下文注入跳过：输入消息中没有 SYSTEM 消息");
            return next.apply(input);
        }

        Msg systemMsg = messages.get(systemIndex);
        String base = StrUtil.blankToDefault(systemMsg.getTextContent(), "");

        StringBuilder sb = new StringBuilder(base);
        for (ContextBlock block : active) {
            if (StrUtil.isBlank(block.content())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(block.content());
        }

        Msg rebuilt = Msg.builder()
                .id(systemMsg.getId())
                .name(systemMsg.getName())
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(sb.toString()).build())
                .build();
        messages.set(systemIndex, rebuilt);

        ReasoningInput modified = new ReasoningInput(messages, input.tools(), input.options());
        return next.apply(modified);
    }
}
