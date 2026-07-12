package com.byteq.ai.ragstudio.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 对话日志配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "ai-log")
public class AiLogProperties {
    /** 是否保存 AI 对话日志到 AILog/ 目录 */
    private boolean saveEnabled = false;
}
