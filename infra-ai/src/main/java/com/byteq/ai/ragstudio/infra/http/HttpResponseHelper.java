package com.byteq.ai.ragstudio.infra.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.byteq.ai.ragstudio.infra.config.AIModelProperties;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import lombok.NoArgsConstructor;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 响应处理工具类
 * <p>
 * 集中管理与 OkHttp 响应相关的公共操作，包括响应体读取、JSON 解析、
 * 以及模型调用前的参数校验（提供商配置、API 密钥、模型名称等）。
 * 该工具类采用私有构造方法，所有方法均为静态方法，作为纯工具函数使用。
 * </p>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class HttpResponseHelper {

    /** 共享的 Gson 实例，用于 JSON 解析操作 */
    private static final Gson GSON = new Gson();

    /**
     * 读取 OkHttp 响应体的原始字节内容并转换为 UTF-8 字符串
     * <p>
     * 与 {@link ResponseBody#string()} 不同，该方法使用 {@link ResponseBody#bytes()} 避免
     * OkHttp 内部对字符串的编码推测，确保始终以 UTF-8 编码读取响应内容。
     * </p>
     *
     * @param body OkHttp 响应体对象，可能为 null
     * @return 响应体的 UTF-8 字符串内容；如果 body 为 null 则返回空字符串
     * @throws IOException 如果读取响应体时发生 I/O 错误
     */
    public static String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return new String(body.bytes(), StandardCharsets.UTF_8);
    }

    /**
     * 将 OkHttp 响应体解析为 Gson 的 JsonObject
     * <p>
     * 先校验响应体非空，然后读取字符串内容并通过 Gson 解析为结构化 JSON 对象。
     * 如果响应体为 null，则抛出 {@link ModelClientException} 异常。
     * </p>
     *
     * @param body  OkHttp 响应体对象
     * @param label 提供商或模型标签，用于在异常消息中标识来源
     * @return 解析后的 JsonObject 对象
     * @throws IOException              如果读取响应体时发生 I/O 错误
     * @throws ModelClientException     如果响应体为 null 或 JSON 格式错误
     */
    public static JsonObject parseJson(ResponseBody body, String label) throws IOException {
        if (body == null) {
            throw new ModelClientException(label + " 响应为空", ModelClientErrorType.INVALID_RESPONSE, null);
        }
        String content = body.string();
        return GSON.fromJson(content, JsonObject.class);
    }

    /**
     * 校验模型目标对象中的提供商配置并返回
     * <p>
     * 检查 {@link ModelTarget#provider()} 是否为空，确保模型调用前提供商配置已正确设置。
     * 在校验失败时抛出 {@link IllegalStateException} 异常。
     * </p>
     *
     * @param target 模型目标对象，包含提供商和候选模型信息
     * @param label  提供商或模型标签，用于在异常消息中标识来源
     * @return 非空的提供商配置对象
     * @throws IllegalStateException 如果 target 为 null 或 provider() 为 null
     */
    public static AIModelProperties.ProviderConfig requireProvider(ModelTarget target, String label) {
        if (target == null || target.provider() == null) {
            throw new IllegalStateException(label + " 提供商配置缺失");
        }
        return target.provider();
    }

    /**
     * 校验提供商配置中的 API 密钥是否已配置
     * <p>
     * 检查 API 密钥不为 null 且不为空白字符串，确保模型调用前认证信息已正确设置。
     * 在校验失败时抛出 {@link IllegalStateException} 异常。
     * </p>
     *
     * @param provider 提供商配置对象
     * @param label    提供商或模型标签，用于在异常消息中标识来源
     * @throws IllegalStateException 如果 API 密钥为 null 或空白
     */
    public static void requireApiKey(AIModelProperties.ProviderConfig provider, String label) {
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw new IllegalStateException(label + " API密钥缺失");
        }
    }

    /**
     * 校验模型目标对象中的模型名称并返回
     * <p>
     * 检查 {@link ModelTarget#candidate()} 及其 model 字段是否为空，
     * 确保模型调用前已明确指定要使用的模型名称。
     * 在校验失败时抛出 {@link IllegalStateException} 异常。
     * </p>
     *
     * @param target 模型目标对象，包含提供商和候选模型信息
     * @param label  提供商或模型标签，用于在异常消息中标识来源
     * @return 非空的模型名称字符串
     * @throws IllegalStateException 如果 target、candidate 或 model 为 null
     */
    public static String requireModel(ModelTarget target, String label) {
        if (target == null || target.candidate() == null || target.candidate().getModel() == null) {
            throw new IllegalStateException(label + " 模型名称缺失");
        }
        return target.candidate().getModel();
    }
}
