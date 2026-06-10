package com.byteq.ai.ragstudio.rag.controller;

import com.byteq.ai.ragstudio.rag.controller.request.ConversationUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationMessageVO;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationVO;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.enums.ConversationMessageOrder;
import com.byteq.ai.ragstudio.rag.service.ConversationMessageService;
import com.byteq.ai.ragstudio.rag.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话控制器
 * <p>
 * 提供会话相关的 REST API 接口，包括会话列表获取、重命名、删除以及会话消息列表获取等功能。
 * 会话是 RAG 多轮对话的基本单位，每个会话包含一组用户与 AI 的消息交互记录。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class ConversationController {

    /**
     * 会话服务，提供会话的 CRUD 操作
     */
    private final ConversationService conversationService;

    /**
     * 会话消息服务，提供会话消息的查询和添加功能
     */
    private final ConversationMessageService conversationMessageService;

    /**
     * 获取当前用户的会话列表
     * <p>
     * 从用户上下文中获取当前用户 ID，查询该用户的所有会话并按最后活动时间倒序返回。
     * </p>
     *
     * @return 当前用户的会话视图对象列表
     */
    @GetMapping("/conversations")
    public Result<List<ConversationVO>> listConversations() {
        return Results.success(conversationService.listByUserId(UserContext.getUserId()));
    }

    /**
     * 重命名会话
     * <p>
     * 更新指定会话的名称，用于用户自定义会话标题。
     * </p>
     *
     * @param conversationId 会话 ID
     * @param request        重命名请求参数，包含新名称
     * @return 操作结果
     */
    @PutMapping("/conversations/{conversationId}")
    public Result<Void> rename(@PathVariable String conversationId,
                               @RequestBody ConversationUpdateRequest request) {
        conversationService.rename(conversationId, request);
        return Results.success();
    }

    /**
     * 删除会话
     * <p>
     * 根据会话 ID 删除指定的会话及其所有消息记录。
     * </p>
     *
     * @param conversationId 会话 ID
     * @return 操作结果
     */
    @DeleteMapping("/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        conversationService.delete(conversationId);
        return Results.success();
    }

    /**
     * 获取会话消息列表
     * <p>
     * 查询指定会话中的所有消息记录，按创建时间升序排列。
     * 返回的消息包含用户问题和 AI 回答，以及深度思考内容（如有）。
     * </p>
     *
     * @param conversationId 会话 ID
     * @return 会话消息视图对象列表，按时间升序排列
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ConversationMessageVO>> listMessages(@PathVariable String conversationId) {
        return Results.success(conversationMessageService.listMessages(conversationId, UserContext.getUserId(), null, ConversationMessageOrder.ASC));
    }
}
