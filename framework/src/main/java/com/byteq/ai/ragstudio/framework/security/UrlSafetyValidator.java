package com.byteq.ai.ragstudio.framework.security;

import com.byteq.ai.ragstudio.framework.exception.ClientException;

import java.net.URI;

/**
 * URL 基础安全校验（管理员配置场景）
 * <p>
 * 用于模型 API 地址、MCP Server 地址等「管理员录入」的外部 URL 校验。
 * 与 {@code SsrfGuard}（用户可控 URL 的严格校验）不同，此处信任内网/回环地址
 * （部署环境模型服务常位于内网），仅做协议与格式层面的防御。
 * </p>
 */
public final class UrlSafetyValidator {

    private UrlSafetyValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 校验 URL 的协议与主机格式是否合法
     *
     * @param url       待校验 URL
     * @param fieldName 字段名（用于错误提示）
     * @return 规范化后的 URL（去空白）
     * @throws ClientException 协议非 http/https 或缺少主机名时抛出
     */
    public static String validateHttpUrl(String url, String fieldName) {
        if (url == null || url.isBlank()) {
            throw new ClientException(fieldName + " 不能为空");
        }
        String trimmed = url.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception e) {
            throw new ClientException(fieldName + " 格式不合法: " + trimmed);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new ClientException(fieldName + " 仅支持 http/https 协议");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ClientException(fieldName + " 缺少主机名: " + trimmed);
        }
        return trimmed;
    }
}
