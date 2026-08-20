package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.infra.chat.StreamCancellationHandle;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

import java.util.List;

/**
 * 模型供应商网关（SDK 优先调用策略）
 * <p>
 * 每家模型厂商对应一个 {@link ProviderGateway} 实现。网关内部决定该厂商的调用策略：
 * <ul>
 *   <li><b>有官方 Java SDK</b>：直接封装厂商官方 SDK（如阿里 DashScope SDK、智谱 zai-sdk、
 *       火山 ark-runtime、OpenAI SDK、Anthropic SDK）；</li>
 *   <li><b>无官方 SDK</b>：走通用协议策略（OpenAI 兼容格式 / Anthropic 格式，通过 baseUrl 适配）。</li>
 * </ul>
 * </p>
 * <p>
 * 与路由层的关系：{@link ProviderGateway} 是「客户端来源」的底层策略，路由层（
 * {@code RoutingLLMService} / {@code RoutingEmbeddingService} / {@code RoutingRerankService}）
 * 仍通过 {@code ChatClient} / {@code EmbeddingClient} / {@code RerankClient} 三种能力接口消费，
 * 由薄适配器在调用期按 {@link ModelTarget} 的协议解析出具体网关再委托。
 * </p>
 */
public interface ProviderGateway {

    /**
     * 主厂商标识（如 bailian / zhipu / volcengine / deepseek / openai / anthropic），用于日志与识别。
     */
    String provider();

    /**
     * 判断本网关是否处理指定厂商在指定协议下的调用。
     *
     * @param providerName 厂商名（如 bailian / deepseek / siliconflow）
     * @param protocolName 协议名（openai / dashscope / anthropic），已归一化（非空）
     * @return true 表示由本网关接管
     */
    boolean supports(String providerName, String protocolName);

    // ==================== Chat ====================

    /**
     * 同步聊天：发送请求并等待完整响应。
     */
    String chat(ChatRequest request, ModelTarget target);

    /**
     * 流式聊天：以增量方式推送内容 / 思考过程，返回取消句柄。
     */
    StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target);

    // ==================== Embedding ====================

    /**
     * 批量文本嵌入，返回顺序与输入一致的向量列表。
     */
    List<List<Float>> embedBatch(List<String> texts, ModelTarget target);

    /**
     * 批量图像嵌入（仅多模态 Embedding 模型支持），默认不支持。
     *
     * @param imageBase64List 图像 Base64 data URI 列表（如 "data:image/jpeg;base64,..."）
     */
    default List<List<Float>> embedImages(List<String> imageBase64List, ModelTarget target) {
        throw new UnsupportedOperationException("当前 Gateway 不支持图像嵌入: " + provider());
    }

    // ==================== Rerank ====================

    /**
     * 语义重排序：按相关性从高到低返回最多 topN 条结果。
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target);
}