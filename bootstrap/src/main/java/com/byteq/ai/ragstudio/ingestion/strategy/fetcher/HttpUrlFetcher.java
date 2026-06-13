package com.byteq.ai.ragstudio.ingestion.strategy.fetcher;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.util.HttpClientHelper;
import com.byteq.ai.ragstudio.ingestion.util.MimeTypeDetector;
import com.byteq.ai.ragstudio.ingestion.util.SsrfGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP/HTTPS 网络文档获取器
 * <p>
 * 负责从指定的 HTTP/HTTPS URL 地址获取文档内容。支持自定义请求头
 * 和身份认证，适用于从 Web 服务器下载文档的场景。
 * </p>
 * <p>
 * 处理逻辑：
 * <ol>
 *   <li>验证 URL 地址的有效性</li>
 *   <li>从凭证信息中构建 HTTP 请求头（支持 Bearer Token 认证）</li>
 *   <li>发起 HTTP GET 请求获取文档内容</li>
 *   <li>从响应中提取文件名和 Content-Type</li>
 *   <li>如果响应未提供 Content-Type，通过文件内容自动检测 MIME 类型</li>
 * </ol>
 * </p>
 */
@Component
@RequiredArgsConstructor
public class HttpUrlFetcher implements DocumentFetcher {

    /**
     * HTTP 客户端辅助工具，用于执行 HTTP 请求
     */
    private final HttpClientHelper httpClientHelper;

    @Override
    public SourceType supportedType() {
        return SourceType.URL;
    }

    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("链接地址不能为空");
        }

        // SSRF 防护：校验 URL 安全性，拒绝访问内网地址
        SsrfGuard.validate(location);

        Map<String, String> headers = buildHeaders(source.getCredentials());
        HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(location, headers);
        String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : resp.fileName();
        String contentType = normalizeContentType(resp.contentType());
        if (!StringUtils.hasText(contentType)) {
            contentType = MimeTypeDetector.detect(resp.body(), fileName);
        }
        return new FetchResult(resp.body(), contentType, fileName);
    }

    /**
     * 从凭证信息中构建 HTTP 请求头
     * <p>
     * 支持将 "token" 凭证自动转换为 "Authorization: Bearer <token>" 请求头格式。
     * </p>
     *
     * @param credentials 凭证信息键值对
     * @return HTTP 请求头映射
     */
    private Map<String, String> buildHeaders(Map<String, String> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        credentials.forEach((k, v) -> {
            if (!StringUtils.hasText(k) || v == null) {
                return;
            }
            if ("token".equalsIgnoreCase(k)) {
                headers.put("Authorization", "Bearer " + v);
            } else {
                headers.put(k, v);
            }
        });
        return headers;
    }

    /**
     * 规范化 Content-Type
     * <p>
     * 去除 Content-Type 中的参数部分（如 "; charset=utf-8"），只保留 MIME 类型。
     * </p>
     *
     * @param contentType 原始 Content-Type 字符串
     * @return 规范化的 MIME 类型
     */
    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        int idx = contentType.indexOf(';');
        return idx > 0 ? contentType.substring(0, idx).trim() : contentType.trim();
    }
}
