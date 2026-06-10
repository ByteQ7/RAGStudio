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
 * 阿里云百炼（BaiLian）ChatClient 实现
 * <p>
 * 阿里云百炼（DashScope）提供通义千问系列模型的 API 访问，
 * 兼容 OpenAI 协议格式，因此继承 {@link AbstractOpenAIStyleChatClient}。
 * <p>
 * 设计意图：
 * - 通过 provider() 返回 "bailian"，供路由层按提供商查找
 * - chat() 方法被 @RagTraceNode 增强，在链路追踪中记录为 "LLM_PROVIDER" 类型节点
 * - 同步调用委托给父类的 doChat() 模板方法
 * - 流式调用委托给父类的 doStreamChat() 模板方法
 * <p>
 * 使用场景：
 * - 通过阿里云百炼平台使用通义千问-Max、通义千问-Plus 等模型
 * - 需要 think 能力（推理链）时，通过 customizeRequestBody 添加 enable_thinking 字段
 */
@Slf4j
@Service
public class BaiLianChatClient extends AbstractOpenAIStyleChatClient {

    /**
     * 获取提供商名称
     *
     * @return "bailian"（对应 ModelProvider.BAI_LIAN）
     */
    @Override
    public String provider() {
        return ModelProvider.BAI_LIAN.getId();
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
    @RagTraceNode(name = "bailian-chat", type = "LLM_PROVIDER")
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
