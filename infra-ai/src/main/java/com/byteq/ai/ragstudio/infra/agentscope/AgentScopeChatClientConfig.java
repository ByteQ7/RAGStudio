package com.byteq.ai.ragstudio.infra.agentscope;

import com.byteq.ai.ragstudio.infra.chat.ChatClient;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 聊天客户端配置
 * <p>
 * 为各模型供应商注册基于 AgentScope 的统一 {@link ChatClient} Bean，
 * 替换原自研 OkHttp 协议层实现（BaiLianChatClient / SiliconFlowChatClient / DeepSeekChatClient）。
 * </p>
 */
@Configuration
public class AgentScopeChatClientConfig {

    @Bean
    public ChatClient bailianAgentScopeChatClient(AgentScopeModelFactory modelFactory) {
        return new AgentScopeChatClient(ModelProvider.BAI_LIAN.getId(), modelFactory);
    }

    @Bean
    public ChatClient siliconFlowAgentScopeChatClient(AgentScopeModelFactory modelFactory) {
        return new AgentScopeChatClient(ModelProvider.SILICON_FLOW.getId(), modelFactory);
    }

    @Bean
    public ChatClient deepSeekAgentScopeChatClient(AgentScopeModelFactory modelFactory) {
        return new AgentScopeChatClient(ModelProvider.DEEPSEEK.getId(), modelFactory);
    }
}
