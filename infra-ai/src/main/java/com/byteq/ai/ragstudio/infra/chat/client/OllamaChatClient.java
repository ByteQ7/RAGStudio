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
 * Ollama 本地推理 ChatClient 实现
 * <p>
 * Ollama 是本地推理服务，支持多种开源模型（如 Llama、Mistral、Qwen 等）的本地部署，
 * 提供 OpenAI 兼容的 API 接口，因此继承 {@link AbstractOpenAIStyleChatClient}。
 * <p>
 * 设计意图：
 * - 通过 provider() 返回 "ollama"，供路由层按提供商查找
 * - Ollama 为本地服务，不需要 API Key，通过覆写 requiresApiKey() 返回 false
 * - chat() 方法被 @RagTraceNode 增强，在链路追踪中记录为 "LLM_PROVIDER" 类型节点
 * - 同步调用委托给父类的 doChat() 模板方法
 * - 流式调用委托给父类的 doStreamChat() 模板方法
 * <p>
 * 使用场景：
 * - 本地开发环境，无需连接外部 API
 * - 内网部署的开源模型推理服务（如 Llama 3、Qwen 2 等）
 * - 数据安全要求高的场景，所有推理在本地完成
 * <p>
 * 注意事项：
 * - requiresApiKey() 返回 false，因此不会在请求头中添加 Authorization
 * - Ollama 的 URL 配置通过 ModelUrlResolver 解析，默认为 http://localhost:11434
 */
@Slf4j
@Service
public class OllamaChatClient extends AbstractOpenAIStyleChatClient {

    /**
     * 获取提供商名称
     *
     * @return "ollama"（对应 ModelProvider.OLLAMA）
     */
    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    /**
     * Ollama 为本地推理服务，不需要 API Key
     *
     * @return false（不发送 Authorization 头）
     */
    @Override
    protected boolean requiresApiKey() {
        return false;
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
    @RagTraceNode(name = "ollama-chat", type = "LLM_PROVIDER")
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
