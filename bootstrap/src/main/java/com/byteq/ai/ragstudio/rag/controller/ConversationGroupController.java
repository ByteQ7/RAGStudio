package com.byteq.ai.ragstudio.rag.controller;

import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.controller.request.ConversationGroupCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.ConversationGroupMoveRequest;
import com.byteq.ai.ragstudio.rag.controller.request.ConversationGroupUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationGroupVO;
import com.byteq.ai.ragstudio.rag.service.ConversationGroupManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话分组控制器
 * <p>
 * 提供元宝式对话分组能力：创建/重命名/删除分组、修改分组专属指令、
 * 会话移动（单条/批量/移出分组）。组内新对话通过 /rag/v3/chat 请求携带 groupId 实现归组。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class ConversationGroupController {

    private final ConversationGroupManager conversationGroupManager;

    /**
     * 获取当前用户的分组列表（按创建时间正序，附带组内会话数量）
     */
    @GetMapping("/conversation-groups")
    public Result<List<ConversationGroupVO>> listGroups() {
        return Results.success(conversationGroupManager.listGroups(UserContext.getUserId()));
    }

    /**
     * 创建分组
     */
    @PostMapping("/conversation-groups")
    public Result<ConversationGroupVO> create(@Valid @RequestBody ConversationGroupCreateRequest request) {
        return Results.success(conversationGroupManager.create(UserContext.getUserId(), request));
    }

    /**
     * 更新分组（重命名 / 修改分组专属指令）
     */
    @PutMapping("/conversation-groups/{groupId}")
    public Result<Void> update(@PathVariable String groupId,
                               @Valid @RequestBody ConversationGroupUpdateRequest request) {
        conversationGroupManager.update(UserContext.getUserId(), groupId, request);
        return Results.success();
    }

    /**
     * 删除分组
     * <p>
     * 组内会话不会被删除，自动移出分组回到未分组区域。
     * </p>
     */
    @DeleteMapping("/conversation-groups/{groupId}")
    public Result<Void> delete(@PathVariable String groupId) {
        conversationGroupManager.delete(UserContext.getUserId(), groupId);
        return Results.success();
    }

    /**
     * 批量移动会话到指定分组
     * <p>
     * groupId 为空表示移出分组；单条移动复用同一接口（conversationIds 传单个即可）。
     * </p>
     */
    @PutMapping("/conversations/group")
    public Result<Void> moveConversations(@Valid @RequestBody ConversationGroupMoveRequest request) {
        conversationGroupManager.moveConversations(
                UserContext.getUserId(),
                request.getConversationIds(),
                request.getGroupId());
        return Results.success();
    }
}
