package com.byteq.ai.ragstudio.framework.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE（Server-Sent Events）发送器封装类
 *
 * <p>该类对 Spring 的 SseEmitter 进行封装，提供了线程安全的事件发送功能，
 * 统一处理连接关闭状态和异常情况。主要用于服务端向客户端推送实时数据流</p>
 */
@Slf4j
public class SseEmitterSender {

    /** Spring SSE 发送器实例 */
    private final SseEmitter emitter;

    /** 连接关闭状态标识 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @param emitter Spring SSE 发射器
     */
    public SseEmitterSender(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
    }

    /**
     * 发送 SSE 事件到客户端
     */
    public void sendEvent(String eventName, Object data) {
        if (closed.get()) {
            return;
        }
        try {
            if (eventName == null) {
                emitter.send(data);
                return;
            }
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            fail(e);
        }
    }

    public void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    public void fail(Throwable throwable) {
        closeWithError(throwable);
        log.warn("SSE send failed", throwable);
    }

    private void closeWithError(Throwable throwable) {
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(throwable);
        }
    }
}
