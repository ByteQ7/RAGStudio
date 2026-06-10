package com.byteq.ai.ragstudio.infra.embedding;

import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
 * AIHubMix 嵌入客户端实现
 * <p>
 * 对接 AIHubMix 平台的文本嵌入服务，通过 OpenAI 兼容协议将文本转换为向量表示。
 * AIHubMix 作为聚合平台，提供了多种嵌入模型的统一访问入口。
 * </p>
 * <p>
 * <b>特性：</b>
 * <ul>
 *   <li>支持批量嵌入，单次请求最大批处理量为 32 条文本</li>
 *   <li>通过 {@link AbstractOpenAIStyleEmbeddingClient} 复用 OpenAI 兼容协议的通用请求逻辑</li>
 *   <li>自动从配置中心读取 AIHubMix 的 API Key 和 Endpoint 信息</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see AbstractOpenAIStyleEmbeddingClient
 * @see ModelProvider#AI_HUB_MIX
 */
@Service
public class AIHubMixEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public AIHubMixEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.AI_HUB_MIX.getId();
    }

    @Override
    protected int maxBatchSize() {
        return 32;
    }
}
