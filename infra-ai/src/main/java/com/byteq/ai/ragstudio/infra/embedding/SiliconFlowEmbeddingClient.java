package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
 * SiliconFlow 嵌入客户端实现
 * <p>
 * 对接 SiliconFlow 平台的文本嵌入服务，通过 OpenAI 兼容协议将文本转换为向量表示。
 * 使用 SiliconFlow 提供的嵌入模型（如 BAAI/bge-large-zh-v1.5 等）进行向量化处理。
 * </p>
 * <p>
 * <b>特性：</b>
 * <ul>
 *   <li>支持批量嵌入，单次请求最大批处理量为 32 条文本</li>
 *   <li>通过 {@link AbstractOpenAIStyleEmbeddingClient} 复用 OpenAI 兼容协议的通用请求逻辑</li>
 *   <li>自动从配置中心读取 SiliconFlow 的 API Key 和 Endpoint 信息</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see AbstractOpenAIStyleEmbeddingClient
 * @see ModelProvider#SILICON_FLOW
 */
@Service
public class SiliconFlowEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public SiliconFlowEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    protected int maxBatchSize() {
        return 32;
    }
}
