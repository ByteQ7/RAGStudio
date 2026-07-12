package com.byteq.ai.ragstudio.rag.config;

import com.byteq.ai.ragstudio.infra.springai.AiLogHolder;
import com.byteq.ai.ragstudio.rag.service.AiLogService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * AI 日志初始化配置
 * <p>将 AiLogHolder 的静态回调与 AiLogService 连接起来。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiLogConfig {

    private final AiLogService aiLogService;
    private final AiLogProperties properties;

    @PostConstruct
    public void init() {
        if (properties.isSaveEnabled()) {
            AiLogHolder.setLogger((taskId, content) -> {
                // 写入到 AiLogService（会按 taskId 分文件）
                aiLogService.write(taskId, content);
            });
            log.info("AI 对话日志已启用，AiLogHolder 回调已注册");
        } else {
            log.info("AI 对话日志未启用（ai-log.save-enabled=false）");
        }
    }
}
