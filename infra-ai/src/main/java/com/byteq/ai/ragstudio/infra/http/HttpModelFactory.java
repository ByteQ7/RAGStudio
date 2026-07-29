package com.byteq.ai.ragstudio.infra.http;

import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.infra.config.DynamicModelConfig;
import com.byteq.ai.ragstudio.infra.model.ModelTarget;
import com.byteq.ai.ragstudio.infra.protocol.ModelProtocol;
import com.byteq.ai.ragstudio.infra.protocol.ProtocolRegistry;
import com.byteq.ai.ragstudio.infra.reasoning.ReasoningRouter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 模型工厂
 * <p>
 * 根据数据库配置提供 LLM API 调用的通用工具方法：
 * URL / API Key / 推理参数解析、请求体构建、S3 图片编码。
 * </p>
 */
@Slf4j
@Component
public class HttpModelFactory {

    private final S3Client s3Client;
    private final ReasoningRouter reasoningRouter;
    private final ProtocolRegistry protocolRegistry;

    public HttpModelFactory(S3Client s3Client, ReasoningRouter reasoningRouter,
                            ProtocolRegistry protocolRegistry) {
        this.s3Client = s3Client;
        this.reasoningRouter = reasoningRouter;
        this.protocolRegistry = protocolRegistry;
    }

    public ReasoningRouter getReasoningRouter() {
        return reasoningRouter;
    }

    // ==================== URL / Key 解析 ====================

    public String resolveBaseUrl(ModelTarget target) {
        DynamicModelConfig.ModelEntry candidate = target.candidate();
        DynamicModelConfig.ProviderEntry provider = target.provider();
        if (candidate.getUrl() != null && !candidate.getUrl().isBlank()) {
            return candidate.getUrl().replaceAll("/+$", "");
        }
        return provider.getUrl().replaceAll("/+$", "");
    }

    public String resolveApiKey(ModelTarget target) {
        return target.provider().getApiKey();
    }

    /**
     * 构建带端点路径的完整 baseUrl（去除 API 后缀，调用方自行拼接 /chat/completions 等）
     */
    public String resolveEndpointUrl(ModelTarget target, String endpointKey) {
        String baseUrl = resolveBaseUrl(target);
        String path = resolveEndpointPath(target, endpointKey);
        if (path == null || path.isBlank()) return baseUrl;
        String trimmed = path.trim();
        for (String suffix : new String[]{"/chat/completions", "/embeddings"}) {
            if (trimmed.endsWith(suffix)) {
                String prefix = trimmed.substring(0, trimmed.length() - suffix.length());
                if (!prefix.isEmpty()) return baseUrl + prefix;
            }
        }
        if (trimmed.startsWith("/")) {
            int lastSlash = trimmed.lastIndexOf('/');
            if (lastSlash > 0) return baseUrl + trimmed.substring(0, lastSlash);
        }
        return baseUrl;
    }

    /**
     * 根据 endpointKey 返回完整的 API 端点 URL（使用配置的端点路径，不截断后缀）
     */
    public String resolveFullUrl(ModelTarget target, String endpointKey, String defaultPath) {
        String baseUrl = resolveBaseUrl(target);
        String path = resolveEndpointPath(target, endpointKey);
        return baseUrl + (path != null ? path : defaultPath);
    }

    private String resolveEndpointPath(ModelTarget target, String endpointKey) {
        Map<String, String> endpoints = target.provider().getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) return null;
        String path = endpoints.get(endpointKey);
        return (path != null && !path.isBlank()) ? path : null;
    }

    // ==================== 推理参数解析 ====================

    public Map<String, Object> resolveReasoningParams(ChatRequest request, ModelTarget target) {
        int thinkingLevel = request.getThinkingLevel() != null ? request.getThinkingLevel() : 0;
        if (reasoningRouter == null || thinkingLevel <= 0) return Map.of();
        return reasoningRouter.route(target.candidate().getModel(), thinkingLevel);
    }

    // ==================== 请求体构建 ====================

    /** 解析协议感知的 Chat URL */
    public String resolveChatUrl(ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        return resolveFullUrl(target, "chat", protocol.resolveChatUrlFallback());
    }

    /** 解析协议感知的 Embedding URL */
    public String resolveEmbeddingUrl(ModelTarget target) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        if ("dashscope".equals(target.protocolName()) || "anthropic".equals(target.protocolName())) {
            return protocol.resolveEmbeddingUrl(resolveBaseUrl(target));
        }
        return resolveFullUrl(target, "embedding", protocol.resolveEmbeddingUrlFallback());
    }

    public Map<String, Object> buildRequestBody(ChatRequest request, ModelTarget target, boolean stream) {
        return buildRequestBody(request, target, stream, null);
    }

    /**
     * 构建请求体，可携带 tools（native function calling）
     */
    public Map<String, Object> buildRequestBody(ChatRequest request, ModelTarget target, boolean stream,
                                                 List<Map<String, Object>> tools) {
        ModelProtocol protocol = protocolRegistry.get(target.protocolName());
        Map<String, Object> reasoningParams = reasoningRouter != null
                ? reasoningRouter.route(target.candidate().getModel(), request.getThinkingLevel() != null ? request.getThinkingLevel() : 0)
                : Map.of();

        // 前置解析 S3 图片 URL → data URI（所有协议都需要）
        List<ChatMessage> messages = new ArrayList<>();
        if (request.getMessages() != null) {
            for (ChatMessage msg : request.getMessages()) {
                ChatMessage processed = msg;
                List<String> imageUrls = msg.getImageUrls();
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    List<String> resolvedUrls = new ArrayList<>();
                    for (String url : imageUrls) {
                        String resolvedDataUri = resolveImageDataUri(url);
                        if (resolvedDataUri != null) resolvedUrls.add(resolvedDataUri);
                    }
                    processed = new ChatMessage(msg.getRole(), msg.getContent());
                    processed.setImageUrls(resolvedUrls);
                    processed.setThinkingContent(msg.getThinkingContent());
                    processed.setThinkingLevel(msg.getThinkingLevel());
                }
                messages.add(processed);
            }
        }

        return protocol.buildChatRequest(target.candidate().getModel(), messages, stream,
                request.getTemperature(), request.getTopP(), request.getMaxTokens(),
                reasoningParams, tools);
    }

    public Map<String, Object> buildRequestBody(ChatRequest request, ModelTarget target) {
        return buildRequestBody(request, target, false);
    }

    // ==================== S3 图片编码 ====================

    public String resolveImageDataUri(String url) {
        if (url == null) return "";
        try {
            if (!url.startsWith("s3://")) return url;
            String path = url.substring(5);
            int slashIdx = path.indexOf('/');
            if (slashIdx < 0) return url;
            String bucket = path.substring(0, slashIdx);
            String key = path.substring(slashIdx + 1);
            byte[] imageBytes = s3Client.getObject(b -> b.bucket(bucket).key(key)).readAllBytes();
            String mimeType = detectMimeType(key);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return "data:" + mimeType + ";base64," + base64;
        } catch (Exception e) {
            log.warn("读取 S3 图片失败，回退到原始 URL: {}", url, e);
            return url;
        }
    }

    private String detectMimeType(String key) {
        if (key == null) return "image/jpeg";
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    // ==================== 兼容接口（原 LangChain4j 缓存清除，现已无缓存） ====================

    public void evict(String modelId) {
        // no-op
    }

    public void evictAll() {
        // no-op
    }
}
