package com.byteq.ai.ragstudio.infra.sdk;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.http.ModelClientErrorType;
import com.byteq.ai.ragstudio.infra.http.ModelClientException;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 网关通用工具：厂商别名匹配、URL 归一化、SDK 异常转译、统一请求构造辅助。
 */
public final class SdkGatewaySupport {

    private SdkGatewaySupport() {
    }

    // ==================== 厂商别名匹配 ====================

    /**
     * 判断供应商名是否命中某组别名（大小写不敏感、忽略空白与横线）。
     */
    public static boolean matchesAlias(String providerName, String... aliases) {
        if (providerName == null) {
            return false;
        }
        String normalized = normalize(providerName);
        for (String alias : aliases) {
            if (normalize(alias).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("[-_\\s]", "");
    }

    // ==================== URL 归一化 ====================

    /**
     * 去除 URL 末尾多余的斜杠。
     */
    public static String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.replaceAll("/+$", "");
    }

    /**
     * DashScope 原生 API 基础地址归一化：
     * 若未携带 /api/v1 前缀则补齐（SDK 默认 base 即 https://dashscope.aliyuncs.com/api/v1）。
     */
    public static String normalizeDashScopeBaseUrl(String baseUrl) {
        String url = stripTrailingSlash(baseUrl);
        if (url == null || url.isBlank()) {
            return url;
        }
        if (url.endsWith("/api/v1") || url.contains("/api/v1/")) {
            return url;
        }
        return url + "/api/v1";
    }

    // ==================== 异常转译 ====================

    /**
     * 将 SDK 抛出的异常统一转译为路由层可识别的 {@link ModelClientException}。
     */
    public static ModelClientException translateError(String provider, Throwable e) {
        if (e instanceof ModelClientException mce) {
            return mce;
        }
        if (e == null) {
            return new ModelClientException(provider + " 调用失败", ModelClientErrorType.PROVIDER_ERROR, null);
        }
        Integer statusCode = extractHttpStatus(e);
        ModelClientErrorType type = statusCode != null
                ? ModelClientErrorType.fromHttpStatus(statusCode)
                : ModelClientErrorType.PROVIDER_ERROR;
        // 原始响应 body 附带在消息中，避免 "400: null" 这类无诊断价值的错误
        String body = extractErrorBody(e);
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String message = provider + " 调用失败: " + detail;
        if (body != null) {
            message += " 响应体: " + body;
        }
        return new ModelClientException(message, type, statusCode, e);
    }

    private static Integer extractHttpStatus(Throwable e) {
        // 逐层向上查找携带状态码的异常（兼容不同 SDK 的异常封装）
        for (Throwable t = e; t != null; t = t.getCause()) {
            try {
                // httpx 风格：getStatus() 返回 Status 对象（getStatusCode()）
                var status = t.getClass().getMethod("getStatus").invoke(t);
                if (status != null) {
                    Object code = status.getClass().getMethod("getStatusCode").invoke(status);
                    if (code instanceof Number n) {
                        return n.intValue();
                    }
                }
            } catch (Exception ignored) {
                // 继续尝试其他风格
            }
            try {
                // openai-java 风格一：Kotlin 属性 statusCode 的 getter 为 getStatusCode()（UnexpectedStatusCodeException）
                Object code = t.getClass().getMethod("getStatusCode").invoke(t);
                if (code instanceof Number n) {
                    return n.intValue();
                }
            } catch (Exception ignored) {
                // 继续尝试其他风格
            }
            try {
                // openai-java 风格二：Kotlin 函数 statusCode()（BadRequestException 等仅声明 override fun statusCode()）
                Object code = t.getClass().getMethod("statusCode").invoke(t);
                if (code instanceof Number n) {
                    return n.intValue();
                }
            } catch (Exception ignored) {
                // 继续向上
            }
        }
        return null;
    }

    /**
     * 从异常链中提取服务端原始响应体（openai-java 的 body()），用于错误诊断。
     * 部分厂商（如硅基流动）的错误 body 无法解析为 OpenAI 标准 ErrorObject，
     * SDK 的 message 只剩 "400: null"，真实原因（"The parameter is invalid"）藏在 body 中。
     */
    private static String extractErrorBody(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            try {
                Object body = t.getClass().getMethod("body").invoke(t);
                if (body != null && !"JsonMissing".equals(body.getClass().getSimpleName())) {
                    String text = String.valueOf(body);
                    if (!text.isBlank() && !"null".equals(text)) {
                        return text;
                    }
                }
            } catch (Exception ignored) {
                // 继续向上
            }
        }
        return null;
    }

    /**
     * 判断异常是否为「参数类」错误（请求参数不被服务端接受）。
     * <p>
     * 用于 OpenAI 兼容 Embedding 的 dimensions 降级判定：部分厂商（如硅基流动
     * bge 系列）不支持 dimensions 参数，传参后返回 400/422。仅当状态码命中
     * 参数类错误或错误消息出现 dimension/matryoshka 关键词时才允许降级重试，
     * 避免掩盖鉴权、网络等其他失败。
     * </p>
     */
    public static boolean isParamError(Throwable e) {
        Integer status = extractHttpStatus(e);
        if (status != null && (status == 400 || status == 422)) {
            return true;
        }
        String msg = e != null ? String.valueOf(e.getMessage()) : "";
        String lower = msg.toLowerCase(Locale.ROOT);
        // 覆盖无法提取状态码时按错误消息兜底（如硅基流动 "The parameter is invalid. Please check again."）
        return lower.contains("dimension") || lower.contains("matryoshka")
                || lower.contains("invalid parameter") || lower.contains("invalid param");
    }

    // ==================== 请求构造辅助 ====================

    /**
     * 将项目统一消息列表转为 OpenAI 风格的角色数组（OBSERVATION → system）。
     */
    public static List<ChatMessage> normalizeMessages(ChatRequest request) {
        List<ChatMessage> list = new ArrayList<>();
        if (request.getMessages() != null) {
            for (ChatMessage msg : request.getMessages()) {
                if (msg.getRole() == null) {
                    list.add(ChatMessage.user(msg.getContent()));
                } else {
                    list.add(msg);
                }
            }
        }
        return list;
    }

    /**
     * 角色映射：项目角色 → 线协议角色字符串。
     */
    public static String wireRole(ChatMessage.Role role) {
        if (role == null || role == ChatMessage.Role.USER) {
            return "user";
        }
        if (role == ChatMessage.Role.ASSISTANT) {
            return "assistant";
        }
        // SYSTEM / OBSERVATION → system
        return "system";
    }

    /**
     * 从 ModelTarget 解析模型名（优先模型级，其次兜底）。
     */
    public static String requireModelName(ModelTarget target) {
        if (target.candidate() == null || target.candidate().getModel() == null
                || target.candidate().getModel().isBlank()) {
            throw new ModelClientException("模型名未配置", ModelClientErrorType.CLIENT_ERROR, null);
        }
        return target.candidate().getModel();
    }

    /**
     * 从 ModelTarget 解析 baseUrl（模型级覆盖 > 供应商级）。
     */
    public static String resolveBaseUrl(ModelTarget target) {
        if (target.candidate() != null && target.candidate().getUrl() != null
                && !target.candidate().getUrl().isBlank()) {
            return stripTrailingSlash(target.candidate().getUrl());
        }
        if (target.provider() != null && target.provider().getUrl() != null
                && !target.provider().getUrl().isBlank()) {
            return stripTrailingSlash(target.provider().getUrl());
        }
        return null;
    }

    /**
     * 解析 SDK 场景下的基础地址（不含资源后缀）。
     * <p>
     * OpenAI / Anthropic 官方 SDK 需要「版本前缀 baseUrl + 自动追加资源路径」，
     * 而 DB 中供应商存储的是「host + endpoints 完整路径」（如
     * {@code https://dashscope.aliyuncs.com} + {@code /compatible-mode/v1/chat/completions}）。
     * 本方法从 endpoints 路径中去掉资源后缀（/chat/completions、/embeddings、/v1/messages…），
     * 得到 SDK base（如 {@code https://dashscope.aliyuncs.com/compatible-mode/v1}）。
     * </p>
     *
     * @param target     模型目标
     * @param endpointKey 端点键（chat / embedding）
     * @return SDK baseUrl；无法推导时返回原始 baseUrl
     */
    public static String resolveSdkBaseUrl(ModelTarget target, String endpointKey) {
        String baseUrl = resolveBaseUrl(target);
        if (baseUrl == null) {
            return null;
        }
        Map<String, String> endpoints = target.provider() != null ? target.provider().getEndpoints() : null;
        String path = endpoints != null ? endpoints.get(endpointKey) : null;
        if (path == null || path.isBlank()) {
            return baseUrl;
        }
        String trimmed = path.trim();
        for (String suffix : new String[]{"/chat/completions", "/embeddings", "/v1/messages", "/completions"}) {
            if (trimmed.endsWith(suffix)) {
                String prefix = trimmed.substring(0, trimmed.length() - suffix.length());
                if (!prefix.isEmpty()) {
                    return stripTrailingSlash(baseUrl) + prefix;
                }
                // 前缀为空：baseUrl 本身已含版本前缀（如 url=/v1 + path=/chat/completions）
                return baseUrl;
            }
        }
        return baseUrl;
    }

    /**
     * 从 ModelTarget 解析 API Key。
     */
    public static String resolveApiKey(ModelTarget target) {
        return target.provider() != null ? target.provider().getApiKey() : null;
    }
}