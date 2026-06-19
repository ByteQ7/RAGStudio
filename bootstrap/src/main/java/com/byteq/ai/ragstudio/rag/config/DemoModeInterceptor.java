package com.byteq.ai.ragstudio.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.errorcode.BaseErrorCode;
import com.byteq.ai.ragstudio.rag.dto.MessageDelta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.Set;

/**
 * 体验环境只读模式拦截器
 */
@Component
@RequiredArgsConstructor
public class DemoModeInterceptor implements HandlerInterceptor {

    private final DemoModeProperties demoModeProperties;
    private final ObjectMapper objectMapper;

    private static final String DEMO_REJECT_MESSAGE = "体验环境仅支持查询操作";

    /**
     * 需要拦截的 SSE 流式接口路径
     */
    private static final Set<String> SSE_PATHS = Set.of("/rag/v3/chat");

    /**
     * 请求预处理，在体验模式下拦截非只读请求
     * <p>
     * 处理流程：
     * 1. 未开启体验模式 → 直接放行
     * 2. OPTIONS 预检请求 → 直接放行
     * 3. GET 请求（非 SSE 流式接口）→ 直接放行
     * 4. SSE 流式接口 → 返回 SSE 格式的拒绝消息
     * 5. 其余写操作 → 返回 JSON 格式的拒绝消息
     * </p>
     *
     * @return true 放行请求，false 拒绝请求
     */
    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        if (!demoModeProperties.getDemoMode()) {
            return true;
        }
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean isSsePath = SSE_PATHS.contains(path);
        if ("GET".equalsIgnoreCase(method) && !isSsePath) {
            return true;
        }
        if (isSsePath) {
            writeSseReject(response);
        } else {
            writeJsonReject(response);
        }
        return false;
    }

    // 向响应写入 SSE 格式的拒绝消息，通过 text/event-stream 返回 reject 事件和 done 事件
    private void writeSseReject(HttpServletResponse response) {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/event-stream;charset=UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("Connection", "keep-alive");
            PrintWriter writer = response.getWriter();
            writer.write("event: reject\ndata: " + objectMapper.writeValueAsString(new MessageDelta("response", DEMO_REJECT_MESSAGE)) + "\n\n");
            writer.write("event: done\ndata: \"[DONE]\"\n\n");
            writer.flush();
        } catch (Exception e) {
            // 记录日志但不抛出异常，避免影响主流程
            org.slf4j.LoggerFactory.getLogger(DemoModeInterceptor.class)
                    .warn("写入SSE拒绝响应失败", e);
        }
    }

    // 向响应写入 JSON 格式的拒绝消息，返回标准 Result 结构的错误响应
    private void writeJsonReject(HttpServletResponse response) {
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            Result<Void> result = new Result<Void>()
                    .setCode(BaseErrorCode.CLIENT_ERROR.code())
                    .setMessage(DEMO_REJECT_MESSAGE);
            response.getWriter().write(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            // 记录日志但不抛出异常，避免影响主流程
            org.slf4j.LoggerFactory.getLogger(DemoModeInterceptor.class)
                    .warn("写入JSON拒绝响应失败", e);
        }
    }
}
