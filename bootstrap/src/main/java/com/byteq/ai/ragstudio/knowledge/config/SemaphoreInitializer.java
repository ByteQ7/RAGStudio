package com.byteq.ai.ragstudio.knowledge.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 文档上传信号量初始化器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemaphoreInitializer {

    private final RedissonClient redissonClient;
    private final RagSemaphoreProperties semaphoreProperties;

    @PostConstruct
    public void documentUploadSemaphoreInitialize() {
        RagSemaphoreProperties.PermitExpirableConfig config = semaphoreProperties.getDocumentUpload();
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(config.getName());

        boolean set = semaphore.trySetPermits(config.getMaxConcurrent());
        if (set) {
            log.info("Initialized document upload semaphore: name={}, maxConcurrent={}",
                    config.getName(), config.getMaxConcurrent());
        } else {
            log.info("Semaphore already initialized (existing permits > 0): name={}, maxConcurrent={}",
                    config.getName(), config.getMaxConcurrent());
        }
    }
}
