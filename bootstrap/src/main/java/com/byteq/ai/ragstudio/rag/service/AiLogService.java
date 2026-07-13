package com.byteq.ai.ragstudio.rag.service;

import com.byteq.ai.ragstudio.rag.config.AiLogProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * AI 对话日志服务
 * <p>
 * 当 save-ai-log=true 时，将每次对话记录写入 AILog/YYYYMMDD/taskId.log。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiLogService {

    private final AiLogProperties properties;

    private static final String LOG_DIR = "AILog";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @PostConstruct
    public void init() {
        if (properties.isSaveEnabled()) {
            log.info("AI 对话日志已启用，保存路径: {}", Paths.get(LOG_DIR).toAbsolutePath());
        }
    }

    /**
     * 写入一条日志到当日的任务文件
     *
     * @param taskId   任务 ID
     * @param content  日志内容
     */
    public void write(String taskId, String content) {
        if (!properties.isSaveEnabled()) return;
        try {
            Path dir = Paths.get(LOG_DIR, LocalDate.now().format(DATE_FMT));
            Files.createDirectories(dir);
            Path file = dir.resolve(taskId + ".log");
            Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("写入 AI 对话日志失败: taskId={}", taskId, e);
        }
    }

    /**
     * 写入一行日志（自动追加换行）
     */
    public void writeln(String taskId, String line) {
        write(taskId, line + "\n");
    }
}
