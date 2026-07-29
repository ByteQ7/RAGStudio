package com.byteq.ai.ragstudio.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG Trace 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.trace")
public class RagTraceProperties {

    /**
     * 是否启用注解式 Trace 采集
     */
    private boolean enabled = true;

    /**
     * 错误信息最大长度，防止落库过大
     */
    private int maxErrorLength = 1000;

    /**
     * RUNNING 状态最大持续时长（分钟），超过该时长将被自动标记为 ERROR
     * 设置为 0 表示不禁用定时清理
     */
    private int staleRunTimeoutMinutes = 10;
}
