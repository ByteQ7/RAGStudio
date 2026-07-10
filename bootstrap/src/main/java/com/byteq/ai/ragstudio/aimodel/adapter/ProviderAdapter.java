package com.byteq.ai.ragstudio.aimodel.adapter;

import java.util.List;

/**
 * AI 供应商适配器接口
 * <p>
 * 用于适配不同 AI 供应商的 API 差异（连通性检查、模型列表获取等），
 * 各供应商通过实现此接口来提供统一的调用方式。
 * </p>
 */
public interface ProviderAdapter {

    /**
     * 检查与该供应商的连接是否正常
     * <p>
     * 通常通过发送一个轻量级的 API 请求（如空对话或模型列表查询）
     * 来验证 API Key 和 API 地址的有效性。
     * </p>
     *
     * @param baseUrl API 基础地址
     * @param apiKey  API 密钥
     * @return 连通性检查结果
     */
    ConnectivityResult checkConnectivity(String baseUrl, String apiKey);

    /**
     * 从供应商拉取可用模型列表
     *
     * @param baseUrl API 基础地址
     * @param apiKey  API 密钥
     * @return 远程模型信息列表
     */
    List<RemoteModelInfo> fetchModels(String baseUrl, String apiKey);

    /**
     * 判断此适配器是否支持指定的供应商名称
     *
     * @param providerName 供应商名称（如 siliconflow、deepseek、bailian）
     * @return true 如果支持
     */
    boolean supports(String providerName);

    /**
     * 连通性检查结果
     */
    record ConnectivityResult(boolean success, long latencyMs, String error) {}

    /**
     * 远程模型信息
     */
    record RemoteModelInfo(
            String modelId,
            String modelName,
            List<String> capabilities,
            boolean supportsThinking,
            boolean supportsMultimodal,
            Integer dimension
    ) {}
}
