package com.byteq.ai.ragstudio.infra.embedding;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.byteq.ai.ragstudio.infra.config.AIModelProperties;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import com.byteq.ai.ragstudio.infra.http.HttpMediaTypes;
import com.byteq.ai.ragstudio.infra.http.HttpResponseHelper;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.http.ModelUrlResolver;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI 兼容协议嵌入客户端的抽象基类
 * <p>
 * 封装了 OpenAI 风格 {@code /v1/embeddings} API 协议的通用请求和响应处理逻辑。
 * 实现了模板方法模式，将可变部分抽象为钩子方法供子类覆写，从而最大程度复用代码。
 * </p>
 * <p>
 * <b>架构设计：</b>
 * <ul>
 *   <li>子类只需实现 {@link #provider()} 和按需覆写钩子方法即可接入新的模型提供商</li>
 *   <li>内置批量嵌入的分批处理逻辑，自动将超过 {@link #maxBatchSize()} 的请求拆分</li>
 *   <li>完整的错误处理：HTTP 状态码检查、JSON 响应校验、网络异常捕获</li>
 *   <li>通过钩子方法 {@link #requiresApiKey()} 和 {@link #customizeRequestBody(JsonObject, ModelTarget)} 支持不同提供商的差异化需求</li>
 * </ul>
 * </p>
 * <p>
 * <b>钩子方法清单：</b>
 * <ul>
 *   <li>{@link #requiresApiKey()} —— 是否需要 API Key 认证（默认 true）</li>
 *   <li>{@link #customizeRequestBody(JsonObject, ModelTarget)} —— 自定义请求体字段（默认添加 encoding_format）</li>
 *   <li>{@link #maxBatchSize()} —— 单次请求最大批量大小（默认不限）</li>
 * </ul>
 * </p>
 *
 * @author byteq
 * @see SiliconFlowEmbeddingClient
 * @see OllamaEmbeddingClient
 * @see AIHubMixEmbeddingClient
 */
@Slf4j
public abstract class AbstractOpenAIStyleEmbeddingClient implements EmbeddingClient {

    protected final OkHttpClient httpClient;

    protected AbstractOpenAIStyleEmbeddingClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ==================== 子类可覆写的钩子方法 ====================

    /**
     * 当前提供商是否需要 API Key 认证
     * <p>
     * 大部分云端服务需要 API Key 认证，但本地部署的服务（如 Ollama）不需要。
     * 覆写此方法返回 false 可跳过 Authorization 头的添加。
     * </p>
     *
     * @return true 表示需要 API Key（默认），false 表示不需要
     */
    protected boolean requiresApiKey() {
        return true;
    }

    /**
     * 自定义请求体中的提供商特有字段
     * <p>
     * 子类可覆写此方法添加或修改请求体中的字段。默认实现添加 {@code encoding_format: "float"}，
     * 该字段是 OpenAI 兼容协议的标准参数，用于指定向量编码格式。
     * </p>
     *
     * @param body   待定制的请求体 JSON 对象
     * @param target 目标模型配置，可从中提取模型特定参数
     */
    protected void customizeRequestBody(JsonObject body, ModelTarget target) {
        body.addProperty("encoding_format", "float");
    }

    /**
     * 单次请求允许的最大批量大小
     * <p>
     * 不同模型提供商对单次批量嵌入请求的大小有不同的限制。
     * 返回 0 或负数表示不限制（由子类根据提供商限制覆写）。
     * </p>
     *
     * @return 最大批量大小，0 或负数表示不限制
     */
    protected int maxBatchSize() {
        return 0;
    }

    // ==================== 接口实现 ====================

    @Override
    public List<Float> embed(String text, ModelTarget target) {
        // 委托给 doEmbed 处理单条文本，取返回列表的第一个元素
        List<List<Float>> result = doEmbed(List.of(text), target);
        return result.get(0);
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts, ModelTarget target) {
        // 空列表直接返回，避免无效的 API 调用
        if (CollUtil.isEmpty(texts)) {
            return Collections.emptyList();
        }
        int batch = maxBatchSize();
        // 如果不需要分批（未配置分批大小或数据量未超限），直接一次请求
        if (batch <= 0 || texts.size() <= batch) {
            return doEmbed(texts, target);
        }

        // 分批处理：按 maxBatchSize 拆分，逐一请求后合并结果
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(texts.size(), null));
        for (int i = 0, n = texts.size(); i < n; i += batch) {
            int end = Math.min(i + batch, n);
            List<String> slice = texts.subList(i, end);
            List<List<Float>> part = doEmbed(slice, target);
            // 将分批结果填回到对应位置，保证输出顺序与输入一致
            for (int k = 0; k < part.size(); k++) {
                results.set(i + k, part.get(k));
            }
        }
        return results;
    }

    // ==================== 模板方法：核心请求逻辑 ====================

    /**
     * 嵌入请求的模板方法
     * <p>
     * 执行完整的 HTTP 请求流程：参数校验 -> URL 解析 -> 请求体构建 ->
     * HTTP 发送 -> 响应解析 -> 错误处理 -> 结果提取。
     * </p>
     *
     * @param texts  待嵌入的文本列表
     * @param target 目标模型配置
     * @return 嵌入向量列表，每个文本对应一个浮点数向量
     * @throws ModelClientException 当请求失败、响应异常或解析错误时抛出
     */
    protected List<List<Float>> doEmbed(List<String> texts, ModelTarget target) {
        // 校验提供商配置是否存在
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        // 如果需要 API Key，校验 API Key 是否已配置
        if (requiresApiKey()) {
            HttpResponseHelper.requireApiKey(provider, provider());
        }

        // 解析请求 URL（支持动态路由和模型映射）
        String url = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.EMBEDDING);

        // 构建请求体
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        body.add("input", inputArray);
        // 如果配置了目标向量维度，添加到请求体中
        if (target.candidate().getDimension() != null) {
            body.addProperty("dimensions", target.candidate().getDimension());
        }
        // 调用子类的定制钩子，允许添加提供商特有的请求体字段
        customizeRequestBody(body, target);

        // 构建 HTTP 请求
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON));
        // 如果需要 API Key 认证，添加 Authorization 请求头
        if (requiresApiKey()) {
            requestBuilder.addHeader("Authorization", "Bearer " + provider.getApiKey());
        }
        Request request = requestBuilder.build();

        // 执行 HTTP 请求并解析响应
        JsonObject json;
        try (Response response = httpClient.newCall(request).execute()) {
            // 检查 HTTP 状态码是否表示成功
            if (!response.isSuccessful()) {
                String errBody = HttpResponseHelper.readBody(response.body());
                log.warn("{} embedding 请求失败: status={}, body={}", provider(), response.code(), errBody);
                throw new ModelClientException(
                        provider() + " embedding 请求失败: HTTP " + response.code(),
                        ModelClientErrorType.fromHttpStatus(response.code()),
                        response.code()
                );
            }
            json = HttpResponseHelper.parseJson(response.body(), provider());
        } catch (IOException e) {
            // 网络层异常（连接超时、DNS 解析失败等）
            throw new ModelClientException(
                    provider() + " embedding 请求失败: " + e.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, e);
        }

        // 检查服务端返回的业务错误（如模型不存在、参数错误等）
        if (json.has("error")) {
            JsonObject err = json.getAsJsonObject("error");
            String code = err.has("code") ? err.get("code").getAsString() : "unknown";
            String msg = err.has("message") ? err.get("message").getAsString() : "unknown";
            throw new ModelClientException(
                    provider() + " embedding 错误: " + code + " - " + msg,
                    ModelClientErrorType.PROVIDER_ERROR, null);
        }

        // 解析响应中的 data 数组
        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) {
            throw new ModelClientException(
                    provider() + " embedding 响应中缺少 data 数组",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }

        // 从每个 data 条目中提取 embedding 向量
        List<List<Float>> results = new ArrayList<>(data.size());
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            JsonArray emb = obj.getAsJsonArray("embedding");
            if (emb == null || emb.isEmpty()) {
                throw new ModelClientException(
                        provider() + " embedding 响应中缺少 embedding 字段",
                        ModelClientErrorType.INVALID_RESPONSE, null);
            }
            List<Float> vector = new ArrayList<>(emb.size());
            for (JsonElement v : emb) {
                vector.add(v.getAsFloat());
            }
            results.add(vector);
        }

        return results;
    }
}
