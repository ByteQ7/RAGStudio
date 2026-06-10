package com.byteq.ai.ragstudio.infra.chat;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;

import java.util.List;

/**
 * 通用大语言模型（LLM）访问接口
 * <p>
 * 用途说明：
 * - 为业务层提供统一的大模型访问能力，屏蔽不同厂商/协议的差异
 * - 支持同步调用（一次性返回完整回答）与流式调用（按 token/片段增量输出）
 * - 可通过不同实现类适配各模型平台，如：
 * - 本地推理（Ollama、LM Studio 等）
 * - 阿里云百炼（DashScope）
 * - DeepSeek / OpenAI / Qwen API
 * - 企业内部推理服务
 * <p>
 * 核心能力：
 * - 标准化 Prompt 构造（system / user / context）
 * - RAG 场景支持（可传入检索到的上下文）
 * - 参数化控制（温度、top_p、max_tokens、stop 等）
 * - 流式 token 输出（配合 StreamCallback）
 * - 模型级 fallback 与健康检查（通过路由层实现）
 * <p>
 * 设计意图：
 * - 采用接口隔离原则，将 LLM 调用抽象为统一的契约
 * - chat() 提供简化默认方法，降低单轮问答的使用门槛
 * - streamChat() 返回 StreamCancellationHandle，赋予调用方取消控制权
 * <p>
 * 注意事项：
 * - 默认方法 chat(String) / streamChat(String) 主要用于简单问答
 * - 复杂场景（带上下文、多轮对话、控制生成参数）需要使用 ChatRequest
 * - 流式模式下需正确处理 cancel()，并确保资源释放
 * - modelId 参数用于显式指定模型，绕过路由选择逻辑
 */
public interface LLMService {

    /**
     * 同步调用（简化模式）
     * <p>
     * 说明：
     * - 仅传入 prompt，不包含上下文、系统提示词、生成参数等
     * - 底层会自动构造 ChatRequest 并直接执行
     * - 返回完整回答字符串
     * <p>
     * 常用场景：
     * - 单轮提问
     * - 偶发性工具调用
     * - 快速测试模型连通性
     *
     * @param prompt 用户问题/提示词
     * @return 模型返回的完整回答
     */
    default String chat(String prompt) {
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .build();
        return chat(req);
    }

    /**
     * 同步调用（高级模式）
     * <p>
     * 说明：
     * - 支持系统提示词（system），消息列表（messages），
     * RAG 上下文（contextChunks），生成参数（temperature 等）
     * - 适用于需要精细控制的大模型调用
     * <p>
     * 设计意图：
     * - 统一请求结构使得上游无需关心底层模型的具体协议差异
     * - 路由层负责将 ChatRequest 转换为各模型商家的实际请求格式
     * <p>
     * 返回：
     * - 一次性完整回答，无流式回调
     *
     * @param request ChatRequest 包含完整配置的请求对象
     * @return 模型返回的完整回答
     * @throws RemoteException 模型调用失败或所有候选模型均不可用时抛出
     */
    String chat(ChatRequest request);

    /**
     * 同步调用（指定模型）
     * <p>
     * 说明：
     * - modelId 为空时等同于 chat(request)，走默认路由
     * - modelId 不为空时只使用指定模型，仍走路由层的健康检查与 fallback
     * <p>
     * 设计意图：
     * - 提供显式指定模型的能力，适用于前端已确定模型、或 A/B 测试场景
     * - 不绕过健康检查，确保即使指定模型也能得到可靠性保障
     *
     * @param request ChatRequest 完整配置的请求
     * @param modelId 指定的模型ID，为空时走默认路由
     * @return 模型返回的完整回答
     * @throws RemoteException 指定模型不可用或调用失败时抛出
     */
    default String chat(ChatRequest request, String modelId) {
        return chat(request);
    }

    /**
     * 流式调用（简化模式）
     * <p>
     * 说明：
     * - 仅传入 prompt，不指定上下文或生成参数
     * - 模型回答将通过 StreamCallback.onContent() 分段推送
     * - 返回取消句柄，可随时通过 handle.cancel() 取消生成
     * <p>
     * 设计意图：
     * - 简化模式为快速集成提供最低成本的接入路径
     * - 取消句柄使前端"停止生成"功能可以直达底层模型
     *
     * @param prompt   用户输入内容
     * @param callback 流式回调处理器
     * @return StreamCancellationHandle 可用于取消推理
     */
    default StreamCancellationHandle streamChat(String prompt, StreamCallback callback) {
        ChatRequest req = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .build();
        return streamChat(req, callback);
    }

    /**
     * 流式调用（高级模式）
     * <p>
     * 说明：
     * - 适用于需要上下文、多轮对话、参数控制的流式推理
     * - 模型输出可能按 token 或按句段推送
     * - 所有增量内容通过 callback.onContent() 回调
     * - 调用结束后必须调用 callback.onComplete()
     * - 出现异常时调用 callback.onError()
     * <p>
     * 设计意图：
     * - 流式模式的核心在于"首包延迟"（TTFT）和"吞吐量"（TPS）的平衡
     * - 路由层实现了逐模型 fallback：当首个模型首包超时时自动切换下一个模型
     * - ProbeStreamBridge 桥接了首包探测与流式回调的衔接问题
     *
     * @param request  ChatRequest 完整配置的请求
     * @param callback 流式回调接口
     * @return StreamCancellationHandle 用于取消推理
     * @throws RemoteException 所有候选模型均启动失败时抛出
     */
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback);
}
