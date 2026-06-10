package com.byteq.ai.ragstudio.rag.controller;

import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.idempotent.IdempotentSubmit;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.config.RAGDefaultProperties;
import com.byteq.ai.ragstudio.rag.controller.request.ChatRequest;
import com.byteq.ai.ragstudio.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话控制器
 * <p>
 * 提供基于 SSE（Server-Sent Events）的流式问答接口和任务停止接口。
 * 用户可通过 POST 请求发起流式对话，通过 POST 请求停止正在进行的对话任务。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    /**
     * RAG 流式对话服务
     */
    private final RAGChatService ragChatService;

    /**
     * RAG 默认配置属性，用于获取 SSE 超时时间等配置
     */
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * 发起 SSE 流式对话
     * <p>
     * 通过 SSE（Server-Sent Events）技术实现流式响应，支持多轮对话、深度思考模式和知识库选择。
     * 使用 @IdempotentSubmit 注解防止同一用户的重复提交。
     * </p>
     *
     * @param request 聊天请求，包含问题文本、会话ID、深度思考模式和选中的知识库ID列表
     * @return SseEmitter SSE 发射器，用于流式返回对话结果
     */
    @IdempotentSubmit(
            key = "T(com.byteq.ai.ragstudio.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @PostMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        ragChatService.streamChat(request.getQuestion(), request.getConversationId(), request.getDeepThinking(), request.getKnowledgeBaseIds(), emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     * <p>
     * 根据任务 ID 停止正在进行的流式对话任务，用于用户主动取消对话。
     * </p>
     *
     * @param taskId 要停止的任务 ID
     * @return 操作结果
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}
