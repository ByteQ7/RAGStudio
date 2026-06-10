package com.byteq.ai.ragstudio.infra.http;

import com.byteq.ai.ragstudio.infra.config.AIModelProperties;
import com.byteq.ai.ragstudio.infra.enums.ModelCapability;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 模型 URL 解析器
 * <p>
 * 用于解析 AI 模型的完整 API 请求地址。支持两种 URL 获取方式，按优先级依次尝试：
 * </p>
 * <ol>
 *   <li><b>候选模型自定义 URL</b>：如果 {@link AIModelProperties.ModelCandidate#getUrl()} 已配置，则直接使用</li>
 *   <li><b>提供商基础 URL + 端点路径</b>：从提供商配置中获取基础 URL，并根据 {@link ModelCapability} 拼接对应的端点路径</li>
 * </ol>
 *
 * <p>该工具类采用私有构造方法，所有方法均为静态方法，作为纯工具函数使用。</p>
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public class ModelUrlResolver {

    /**
     * 解析模型请求的完整 URL 地址
     * <p>
     * URL 解析优先级：
     * <ol>
     *   <li>优先使用候选模型配置中自定义的 URL（{@link AIModelProperties.ModelCandidate#getUrl()}）</li>
     *   <li>回退使用提供商配置中的基础 URL（{@link AIModelProperties.ProviderConfig#getUrl()}）
     *       拼接对应能力类型的端点路径（{@link AIModelProperties.ProviderConfig#getEndpoints()}）</li>
     * </ol>
     * </p>
     *
     * @param provider   提供商配置，包含基础 URL 和端点映射信息
     * @param candidate  候选模型配置，可能包含自定义 URL
     * @param capability 模型能力类型（CHAT / EMBEDDING / RERANK），用于确定使用哪个端点路径
     * @return 完整的模型请求 URL 地址
     * @throws IllegalStateException 当提供商基础 URL 缺失或对应能力的端点路径配置缺失时抛出
     */
    public static String resolveUrl(
            AIModelProperties.ProviderConfig provider,
            AIModelProperties.ModelCandidate candidate,
            ModelCapability capability) {
        // 优先级1：候选模型配置了自定义 URL，直接使用
        if (candidate != null && candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl();
        }
        // 优先级2：使用提供商基础 URL，但必须先校验其存在
        if (provider == null || provider.getUrl() == null || provider.getUrl().isBlank()) {
            throw new IllegalStateException("Provider baseUrl is missing");
        }

        // 根据模型能力类型获取对应的端点路径（如 chat -> /v1/chat/completions）
        Map<String, String> endpoints = provider.getEndpoints();
        String key = capability.name().toLowerCase();
        String path = endpoints == null ? null : endpoints.get(key);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("Provider endpoint is missing: " + key);
        }

        // 将基础 URL 与端点路径拼接为完整的请求 URL
        return joinUrl(provider.getUrl(), path);
    }

    /**
     * 拼接基础 URL 和路径
     * <p>
     * 自动处理基础 URL 与路径之间的斜杠分隔符，避免出现重复斜杠或缺少斜杠的问题。
     * 处理策略：
     * </p>
     * <ul>
     *   <li>基础 URL 以 "/" 结尾 且 路径以 "/" 开头：移除路径开头的 "/" 再拼接</li>
     *   <li>基础 URL 不以 "/" 结尾 且 路径不以 "/" 开头：在中间补上 "/" 再拼接</li>
     *   <li>其他情况：直接拼接</li>
     * </ul>
     *
     * @param baseUrl 基础 URL，如 "https://api.example.com"
     * @param path    端点路径，如 "/v1/chat/completions" 或 "v1/chat/completions"
     * @return 拼接后的完整 URL
     */
    private static String joinUrl(String baseUrl, String path) {
        // 基础 URL 以 "/" 结尾且路径以 "/" 开头，去除路径的 "/" 前缀避免双斜杠
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl + path.substring(1);
        }
        // 基础 URL 不以 "/" 结尾且路径不以 "/" 开头，中间补上 "/"
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        // 其他情况直接拼接（一个以 "/" 结尾、另一个不以 "/" 开头，或反之）
        return baseUrl + path;
    }
}
