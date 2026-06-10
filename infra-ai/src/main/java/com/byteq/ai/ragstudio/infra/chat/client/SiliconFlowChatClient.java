package com.byteq.ai.ragstudio.infra.chat.client;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.infra.chat.AbstractOpenAIStyleChatClient;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * SiliconFlow（硅基流动）ChatClient 实现
 * <p>
 * SiliconFlow 提供多种开源模型（如 DeepSeek-V2、Qwen2、GLM-4 等）的 API 访问，
 * 兼容 OpenAI 协议格式，因此继承 {@link AbstractOpenAIStyleChatClient}。
 * <p>
 * 设计意图：
 * - 通过 provider() 返回 "siliconflow"，供路由层按提供商查找
 * - chat() 方法被 @RagTraceNode 增强，在链路追踪中记录为 "LLM_PROVIDER" 类型节点
 * - 同步调用委托给父类的 doChat() 模板方法
 * - 流式调用委托给父类的 doStreamChat() 模板方法
 * <p>
 * 使用场景：
 * - 通过 SiliconFlow 平台使用 DeepSeek、Qwen、GLM 等开源模型
 * - DeepSeek-R1 等推理模型的 reasoning_content 通过 isReasoningEnabledForStream 控制
 */
@Slf4j
@Service
public class SiliconFlowChatClient extends AbstractOpenAIStyleChatClient {

    /**
     * 获取提供商名称
     *
     * @return "siliconflow"（对应 ModelProvider.SILICON_FLOW）
     */
    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    /**
     * 同步聊天
     * <p>
     * 委托父类的 doChat 模板方法执行，被 @RagTraceNode 增强。
     *
     * @param request 聊天请求
     * @param target  目标模型配置
     * @return 模型回答文本
     */
    @Override
    @RagTraceNode(name = "siliconflow-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request, target);
    }

    /**
     * 流式聊天
     * <p>
     * 委托父类的 doStreamChat 模板方法执行。
     *
     * @param request  聊天请求
     * @param callback 流式回调
     * @param target   目标模型配置
     * @return 流式取消句柄
     */
    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request, callback, target);
    }
}
