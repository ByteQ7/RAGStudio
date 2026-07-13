package com.byteq.ai.ragstudio.infra.springai;

import java.util.function.BiConsumer;

/**
 * AI 供应商回复日志持有者
 * <p>由 bootstrap 模块的 Spring Bean 在启动时初始化，infra-ai 模块静态调用。</p>
 */
public class AiLogHolder {

    /** (taskId, content) → 写入日志文件 */
    private static BiConsumer<String, String> logger = null;

    public static void setLogger(BiConsumer<String, String> logger) {
        AiLogHolder.logger = logger;
    }

    public static boolean isEnabled() {
        return logger != null;
    }

    public static void log(String taskId, String content) {
        if (logger != null) {
            logger.accept(taskId, content);
        }
    }
}
