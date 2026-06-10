package com.byteq.ai.ragstudio.infra.chat;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

/**
 * 聊天客户端接口
 * <p>
 * 定义了与 AI 模型进行对话的核心方法，是 LLMService 与具体模型提供商之间的适配层。
 * <p>
 * 设计意图：
 * - 每个实现类对应一个模型提供商（如阿里云百炼、SiliconFlow、Ollama）
 * - 采用接口隔离 + 模板方法模式：AbstractOpenAIStyleChatClient 封装了 OpenAI 兼容协议的公共逻辑
 * - provider() 用于标识提供商，驱动路由层的客户端查找与匹配
 * <p>
 * 本接口与 {@link LLMService} 的区别：
 * - LLMService：面向业务层，支持多模型路由、fallback、trace 等横切关注点
 * - ChatClient：面向基础设施层，专注于单一模型商家的协议适配
 */
public interface ChatClient {

    /**
     * 获取模型服务提供商名称
     * <p>
     * 返回值用于路由层按提供商查找对应的 ChatClient 实例，
     * 取值来自 {@link ModelProvider} 枚举的各提供商 ID。
     *
     * @return 服务提供商标识，如 "bailian"、"siliconflow"、"ollama"
     */
    String provider();

    /**
     * 同步聊天方法
     * <p>
     * 发送请求并等待模型完整响应后返回，适用于对实时性要求不高、
     * 但需要完整上下文的场景（如离线批处理、摘要生成）。
     * <p>
     * 实现说明：
     * - 由 AbstractOpenAIStyleChatClient.doChat() 提供模板实现
     * - 子类通过 @RagTraceNode 注解注入 trace 埋点
     *
     * @param request 聊天请求对象，包含用户消息和对话历史
     * @param target  目标模型配置，指定使用的具体模型及候选配置
     * @return 模型返回的完整响应文本
     * @throws ModelClientException 请求失败、响应格式异常时抛出
     */
    String chat(ChatRequest request, ModelTarget target);

    /**
     * 流式聊天方法
     * <p>
     * 以流式方式接收模型响应，适用于实时展示场景（如类 ChatGPT 的逐字输出）。
     * <p>
     * 实现说明：
     * - 由 AbstractOpenAIStyleChatClient.doStreamChat() 提供模板实现
     * - 使用 SSE（Server-Sent Events）协议接收模型输出
     * - 响应内容通过 callback.onContent() 逐段回调
     * - 返回的 StreamCancellationHandle 使调用方可随时中断生成
     * <p>
     * 超时处理：
     * - 首包超时由路由层的 ProbeStreamBridge + LlmFirstPacketProbe 共同处理
     * - 流式传输过程中的网络超时由 OkHttpClient 的超时配置控制
     *
     * @param request  聊天请求对象，包含用户消息和对话历史
     * @param callback 流式回调接口，用于接收增量响应片段
     * @param target   目标模型配置，指定使用的具体模型及候选配置
     * @return 流取消处理器，可用于中断正在进行的流式响应
     */
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);
}
