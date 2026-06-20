package com.byteq.ai.ragstudio.rag.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 可 Flush 的 SseEmitter
 * <p>
 * Spring 的 {@link SseEmitter#send(Object)} 不会自动 flush 输出缓冲区，
 * 导致 SSE 事件积压在 Tomcat 缓冲区中，客户端无法实时收到。
 * 此封装在每次 send 后手动调用 {@link HttpServletResponse#flushBuffer()}。
 * </p>
 * <p>
 * 使用方式：在 Controller 中用此类代替 {@link SseEmitter}，</p>
 */
public class FlushableSseEmitter extends SseEmitter {

    private final HttpServletResponse response;

    public FlushableSseEmitter(long timeout, HttpServletResponse response) {
        super(timeout);
        this.response = response;
    }

    @Override
    public void send(Object object) throws IOException {
        super.send(object);
        flushResponse();
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        super.send(builder);
        flushResponse();
    }

    private void flushResponse() {
        if (response != null) {
            try {
                response.flushBuffer();
            } catch (Exception ignored) {
                // flush 失败不影响主流程
            }
        }
    }
}
