package com.byteq.ai.ragstudio.ingestion.strategy.fetcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.util.HttpClientHelper;
import com.byteq.ai.ragstudio.ingestion.util.MimeTypeDetector;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书文档获取器
 * <p>
 * 负责从飞书（Feishu/Lark）开放平台获取文档内容。支持两种文档类型的获取：
 * <ul>
 *   <li>在线文档（docx/docs 类型）：通过飞书 Open API 获取文档的纯文本内容</li>
 *   <li>其他文件类型：直接通过 HTTP 下载二进制文件</li>
 * </ul>
 * </p>
 * <p>
 * 处理逻辑：
 * <ol>
 *   <li>解析飞书 API 访问凭证（支持预置 Token 或 App ID + App Secret 自动获取）</li>
 *   <li>判断文档类型：如果是 docx/docs 类型，调用飞书文档 Open API 获取内容</li>
 *   <li>如果是其他文件类型，直接通过 HTTP 下载</li>
 *   <li>自动检测文档的 MIME 类型</li>
 * </ol>
 * </p>
 */
@Component
@RequiredArgsConstructor
public class FeishuFetcher implements DocumentFetcher {

    /**
     * 飞书租户访问令牌获取接口地址
     */
    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal/";

    /**
     * 同步 HTTP 客户端，用于飞书 API 调用
     */
    @Qualifier("syncHttpClient")
    private final OkHttpClient okHttpClient;

    /**
     * HTTP 客户端辅助工具
     */
    private final HttpClientHelper httpClientHelper;

    @Override
    public SourceType supportedType() {
        return SourceType.FEISHU;
    }

    @Override
    public FetchResult fetch(DocumentSource source) {
        String location = source.getLocation();
        if (!StringUtils.hasText(location)) {
            throw new ServiceException("飞书文档地址不能为空");
        }

        String accessToken = resolveAccessToken(source.getCredentials());
        Map<String, String> headers = new HashMap<>();
        if (StringUtils.hasText(accessToken)) {
            headers.put("Authorization", "Bearer " + accessToken);
        }

        // 判断是否为飞书在线文档类型
        if (isDocxUrl(location)) {
            String docToken = extractDocToken(location);
            String apiUrl = "https://open.feishu.cn/open-apis/docx/v1/documents/" + docToken + "/raw_content";
            HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(apiUrl, headers);
            String content = extractDocxContent(resp.body());
            if (!StringUtils.hasText(content)) {
                content = new String(resp.body(), StandardCharsets.UTF_8);
            }
            String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : docToken + ".txt";
            return new FetchResult(content.getBytes(StandardCharsets.UTF_8), "text/plain", fileName);
        }

        // 非在线文档类型，直接通过 HTTP 下载
        HttpClientHelper.HttpFetchResponse resp = httpClientHelper.get(location, headers);
        String fileName = StringUtils.hasText(source.getFileName()) ? source.getFileName() : resp.fileName();
        String contentType = resp.contentType();
        if (!StringUtils.hasText(contentType)) {
            contentType = MimeTypeDetector.detect(resp.body(), fileName);
        }
        return new FetchResult(resp.body(), contentType, fileName);
    }

    /**
     * 判断 URL 是否为飞书在线文档链接
     *
     * @param location 文档 URL
     * @return true 如果是 docx 或 docs 类型的文档链接
     */
    private boolean isDocxUrl(String location) {
        return location.contains("/docx/") || location.contains("/docs/");
    }

    /**
     * 从飞书文档 URL 中提取文档令牌（docToken）
     * <p>
     * 例如：{@code https://xxx.feishu.cn/docx/AbCdEfGh1234?from=xxx}
     * 提取结果为 {@code AbCdEfGh1234}。
     * </p>
     *
     * @param location 飞书文档 URL
     * @return 文档令牌
     */
    private String extractDocToken(String location) {
        String[] parts = location.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("docx".equalsIgnoreCase(parts[i]) || "docs".equalsIgnoreCase(parts[i])) {
                if (i + 1 < parts.length) {
                    String token = parts[i + 1];
                    int queryIndex = token.indexOf('?');
                    return queryIndex > 0 ? token.substring(0, queryIndex) : token;
                }
            }
        }
        throw new ServiceException("无法从飞书链接解析文档令牌: " + location);
    }

    /**
     * 解析飞书 API 访问凭证
     * <p>
     * 支持三种凭证获取方式（按优先级）：
     * <ol>
     *   <li>直接使用预置的 tenantAccessToken</li>
     *   <li>使用预置的 accessToken</li>
     *   <li>使用 app_id + app_secret 通过飞书 API 自动获取 tenant_access_token</li>
     * </ol>
     * </p>
     *
     * @param credentials 凭证信息映射
     * @return 访问令牌，如果无法获取则返回 null
     */
    private String resolveAccessToken(Map<String, String> credentials) {
        if (credentials == null) {
            return null;
        }
        String token = credentials.get("tenantAccessToken");
        if (!StringUtils.hasText(token)) {
            token = credentials.get("accessToken");
        }
        if (StringUtils.hasText(token)) {
            return token;
        }
        String appId = credentials.get("app_id");
        String appSecret = credentials.get("app_secret");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            return null;
        }
        return requestTenantAccessToken(appId, appSecret);
    }

    /**
     * 通过飞书开放 API 请求租户访问令牌
     * <p>
     * 使用应用凭证（App ID + App Secret）调用飞书身份认证接口，
     * 获取 tenant_access_token 用于后续 API 调用。
     * </p>
     *
     * @param appId     飞书应用的 App ID
     * @param appSecret 飞书应用的 App Secret
     * @return 租户访问令牌
     */
    private String requestTenantAccessToken(String appId, String appSecret) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("app_id", appId);
            payload.addProperty("app_secret", appSecret);

            Request request = new Request.Builder()
                    .url(TOKEN_URL)
                    .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")))
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new ServiceException("飞书令牌请求失败: " + response.code());
                }
                String raw = response.body().string();
                JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
                if (json.has("tenant_access_token")) {
                    return json.get("tenant_access_token").getAsString();
                }
                return null;
            }
        } catch (Exception e) {
            throw new ServiceException("飞书令牌请求失败: " + e.getMessage());
        }
    }

    /**
     * 从飞书 API 响应中提取文档内容
     * <p>
     * 飞书文档 Open API 返回的 JSON 结构中，文档内容位于
     * {@code data.content} 字段中。
     * </p>
     *
     * @param bytes API 响应字节数组
     * @return 文档内容文本，如果解析失败则返回 null
     */
    private String extractDocxContent(byte[] bytes) {
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("data")) {
                JsonObject data = root.getAsJsonObject("data");
                if (data.has("content")) {
                    return data.get("content").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
