package com.byteq.ai.ragstudio.core.parser.mineru;

/**
 * MinerU 服务端点值对象
 * <p>
 * 统一封装「本地 / 远程」两种 MinerU 服务的连接信息，运行时由
 * {@link MineruConfigService} 依据数据库配置（{@code t_mineru_config}）
 * 与静态默认值（{@link MineruProperties}）解析而来。
 * </p>
 * <p>
 * 协议由 {@link MineruEndpointType} 区分：{@code LOCAL} 走 mineru-api 同步
 * {@code /file_parse}；{@code CLOUD_AGENT} 走 mineru.net 官方 Agent 轻量 API
 * （免费免 Token，异步任务流）。
 * </p>
 *
 * @param baseUrl  base URL，如 {@code http://127.0.0.1:8000} 或 {@code https://mineru.net/api/v1/agent}
 * @param backend  解析引擎：pipeline / vlm / hybrid（云端 Agent API 固定轻量 pipeline，此字段忽略）
 * @param lang     语言分组：ch / en 等
 * @param apiKey   可选 API Key（Agent 免费接口无需；预留精准解析 v4 接口鉴权用）
 * @param type     端点协议类型
 */
public record MineruEndpoint(String baseUrl, String backend, String lang, String apiKey, MineruEndpointType type) {

    /**
     * 兼容构造器：按 baseUrl 自动推断协议类型（含 mineru.net 视为官方云端）
     */
    public MineruEndpoint(String baseUrl, String backend, String lang, String apiKey) {
        this(baseUrl, backend, lang, apiKey, defaultType(baseUrl));
    }

    /**
     * 按 baseUrl 推断端点协议类型
     */
    public static MineruEndpointType defaultType(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.toLowerCase();
        return url.contains("mineru.net") ? MineruEndpointType.CLOUD_AGENT : MineruEndpointType.LOCAL;
    }

    /**
     * 端点是否已配置可用（baseUrl 非空即视为可用，健康性另由探测判断）
     */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * 是否为云端异步协议（mineru.net 官方 Agent API）
     */
    public boolean isCloud() {
        return type == MineruEndpointType.CLOUD_AGENT;
    }

    /**
     * 判断是否为本地部署（无 apiKey、baseUrl 含 localhost/127.0.0.1，用于日志标注）
     */
    public boolean isLocal() {
        if (isCloud()) {
            return false;
        }
        String url = baseUrl == null ? "" : baseUrl.toLowerCase();
        return url.contains("localhost") || url.contains("127.0.0.1");
    }

    /**
     * 本地协议的 /file_parse 接口完整地址
     */
    public String parseUrl() {
        return joinUrl("/file_parse");
    }

    /**
     * 拼接路径到 baseUrl（去除结尾斜杠后追加 path）
     */
    public String joinUrl(String path) {
        String url = baseUrl;
        if (url == null || url.isBlank()) {
            return "";
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + path;
    }
}
