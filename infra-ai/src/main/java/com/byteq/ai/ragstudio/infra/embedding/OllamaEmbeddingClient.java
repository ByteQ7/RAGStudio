package com.byteq.ai.ragstudio.infra.embedding;

import com.google.gson.JsonObject;
import com.byteq.ai.ragstudio.infra.enums.ModelProvider;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;

/**
 * Ollama 本地嵌入客户端实现
 * <p>
 * 对接本地 Ollama 服务的文本嵌入能力，通过 OpenAI 兼容协议将文本转换为向量表示。
 * Ollama 是本地部署的模型运行时，支持在本地运行各种开源嵌入模型（如 nomic-embed-text、mxbai-embed-large 等）。
 * </p>
 * <p>
 * <b>与云端服务的差异：</b>
 * <ul>
 *   <li>不需要 API Key 认证（{@link #requiresApiKey()} 返回 false），请求头中不携带 Authorization</li>
 *   <li>不需要 encoding_format 请求体参数（Ollama 固定返回 float 格式的向量）</li>
 *   <li>适用于本地开发调试和数据安全要求较高的场景</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see AbstractOpenAIStyleEmbeddingClient
 * @see ModelProvider#OLLAMA
 */
@Service
public class OllamaEmbeddingClient extends AbstractOpenAIStyleEmbeddingClient {

    public OllamaEmbeddingClient(OkHttpClient syncHttpClient) {
        super(syncHttpClient);
    }

    @Override
    public String provider() {
        return ModelProvider.OLLAMA.getId();
    }

    /**
     * Ollama 为本地服务，无需 API Key 认证
     */
    @Override
    protected boolean requiresApiKey() {
        return false;
    }

    /**
     * Ollama 的嵌入 API 不要求 encoding_format 参数，覆写为空操作以避免发送不必要的字段
     */
    @Override
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
    }
}
