package com.byteq.ai.ragstudio.core.parser.mineru;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MinerU 解析服务配置属性（application.yaml 默认值）
 * <p>
 * 提供 MinerU 服务的本地/远程端点默认配置。运行时可通过
 * {@code t_mineru_config} 表配置覆盖（见 {@link MineruConfigService}），
 * 本类仅提供静态默认兜底，保证未部署 MinerU 时也能安全回退。
 * </p>
 * <pre>
 * 示例配置：
 *
 * mineru:
 *   enabled: false          # 全局开关（未部署时默认关闭）
 *   local:
 *     base-url: http://127.0.0.1:8000
 *     backend: pipeline
 *     lang: ch
 *   remote:
 *     # mineru.net 官方 Agent 轻量 API（免费免 Token）：https://mineru.net/api/v1/agent
 *     base-url: https://mineru.net/api/v1/agent
 *     api-key: ""
 *     backend: pipeline
 *     lang: ch
 *   timeout-seconds: 300    # 单次解析超时（首次加载模型可能较慢）
 *   connect-timeout-seconds: 5
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mineru")
public class MineruProperties {

    /**
     * 全局启用开关。false 表示完全未部署 MinerU，一律走 Tika/多模态兜底。
     */
    private boolean enabled = false;

    /**
     * 本地端点配置
     */
    private Endpoint local = new Endpoint();

    /**
     * 远程端点配置（默认指向 mineru.net 官方 Agent 轻量 API，免费免 Token）
     */
    private Endpoint remote = new Endpoint("https://mineru.net/api/v1/agent");

    /**
     * 单次解析超时（秒），默认 300s（首次加载模型可能需 60–120s）
     */
    private long timeoutSeconds = 300;

    /**
     * 连接超时（秒），默认 5s
     */
    private long connectTimeoutSeconds = 5;

    /**
     * MinerU 文本结果的最小有效长度阈值
     * <p>
     * 低于该阈值判定 MinerU 解析结果不达标（如扫描件 OCR 失败），
     * 触发回退多模态 LLM。与 {@code DocumentVisionExtractor.MIN_TEXT_LENGTH} 对齐。
     * </p>
     */
    private int minTextLength = 50;

    /**
     * MinerU 服务端点配置
     */
    @Data
    public static class Endpoint {

        public Endpoint() {
        }

        public Endpoint(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * base URL，如 http://127.0.0.1:8000 或 https://mineru.net/api/v1/agent
         */
        private String baseUrl;

        /**
         * 解析引擎：pipeline / vlm / hybrid
         */
        private String backend = "pipeline";

        /**
         * 语言分组，如 ch / en / auto
         */
        private String lang = "ch";

        /**
         * 可选 API Key（远程服务鉴权用）
         */
        private String apiKey;
    }
}