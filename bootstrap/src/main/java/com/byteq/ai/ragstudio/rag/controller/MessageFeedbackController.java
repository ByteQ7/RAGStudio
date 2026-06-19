package com.byteq.ai.ragstudio.rag.controller;

import com.byteq.ai.ragstudio.rag.controller.request.MessageFeedbackRequest;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.service.MessageFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话消息反馈控制器
 * <p>
 * 提供用户对 AI 回答的点赞/踩反馈接口，反馈数据通过消息队列异步持久化。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class MessageFeedbackController {

    /**
     * 消息反馈服务，处理点赞/踩的异步提交
     */
    private final MessageFeedbackService feedbackService;

    /**
     * 提交点赞/踩反馈（异步，通过 MQ 持久化）
     *
     * @param messageId 消息 ID
     * @param request   反馈请求，包含投票值、原因和补充说明
     * @return 操作结果
     */
    @PostMapping("/conversations/messages/{messageId}/feedback")
    public Result<Void> submitFeedback(@PathVariable String messageId,
                                       @RequestBody MessageFeedbackRequest request) {
        feedbackService.submitFeedbackAsync(messageId, request);
        return Results.success();
    }
}
