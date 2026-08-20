package com.byteq.ai.ragstudio.rag.service.ratelimit;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.rag.service.ratelimit.FairDistributedRateLimiter.AcquireRequest;
import com.byteq.ai.ragstudio.framework.web.SseEmitterSender;
import com.byteq.ai.ragstudio.rag.config.MemoryProperties;
import com.byteq.ai.ragstudio.rag.config.RAGRateLimitProperties;
import com.byteq.ai.ragstudio.rag.core.memory.ConversationMemoryService;
import com.byteq.ai.ragstudio.rag.dto.CompletionPayload;
import com.byteq.ai.ragstudio.rag.dto.MessageDelta;
import com.byteq.ai.ragstudio.rag.dto.MetaPayload;
import com.byteq.ai.ragstudio.rag.enums.SSEEventType;
import com.byteq.ai.ragstudio.rag.service.ConversationGroupService;
import com.byteq.ai.ragstudio.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SSE 全局并发限流入口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQueueLimiter {

    private static final String REJECT_MESSAGE = "系统繁忙，请稍后再试";
    private static final String RESPONSE_TYPE = "response";

    private final FairDistributedRateLimiter chatRateLimiter;
    private final Executor chatEntryExecutor;
    private final RAGRateLimitProperties rateLimitProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final MemoryProperties memoryProperties;
    private final StreamTaskManager taskManager;

    /**
     * 将对话请求入队到全局并发限流器
     * <p>
     * 限流关闭时直接提交到线程池；限流开启时通过公平分布式限流器排队，
     * 获取到 permit 后执行 onAcquire 回调，超时则走 reject 流程。
     * </p>
     *
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param emitter        SSE 发射器
     * @param onAcquire      获取到并发许可后执行的业务逻辑
     */
    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire) {
        enqueue(question, conversationId, emitter, onAcquire, null);
    }

    /**
     * 将对话请求入队到全局并发限流器
     * <p>
     * 限流关闭时直接提交到线程池；限流开启时通过公平分布式限流器排队，
     * 获取到 permit 后执行 onAcquire 回调，超时则走 reject 流程。
     * </p>
     *
     * @param question            用户问题
     * @param conversationId      会话 ID
     * @param emitter             SSE 发射器
     * @param onAcquire           获取到并发许可后执行的业务逻辑
     * @param preRegisteredTaskId 请求入口已注册的 StreamChatEventHandler 的 taskId
     *                            （拒绝时需要先注销，避免 complete() 触发取消回调产生脏数据）
     */
    public void enqueue(String question, String conversationId, SseEmitter emitter, Runnable onAcquire,
                        String preRegisteredTaskId) {
        if (!Boolean.TRUE.equals(rateLimitProperties.getGlobalEnabled())) {
            try {
                chatEntryExecutor.execute(onAcquire);
            } catch (RejectedExecutionException ex) {
                log.warn("直通分支线程池拒绝任务，转 reject 流程", ex);
                handleReject(question, conversationId, emitter, preRegisteredTaskId);
            }
            return;
        }

        chatRateLimiter.acquire(AcquireRequest.builder()
                .maxWaitMillis(TimeUnit.SECONDS.toMillis(rateLimitProperties.getGlobalMaxWaitSeconds()))
                .onAcquired(onAcquire)
                .onTimeout(() -> handleReject(question, conversationId, emitter, preRegisteredTaskId))
                .onAcquiredExecutor(chatEntryExecutor)
                .cancelBinder(cancel -> {
                    emitter.onCompletion(cancel);
                    emitter.onTimeout(cancel);
                    emitter.onError(e -> cancel.run());
                })
                .build());
    }

    // ==================== Reject 业务 ====================

    // 处理被限流拒绝的请求: 记录拒绝会话 -> 向前端发送拒绝事件
    // public: 会话并发门闸（ConversationConcurrencyGate）拒绝同会话并发请求时复用同一拒绝协议
    public void handleReject(String question, String conversationId, SseEmitter emitter) {
        handleReject(question, conversationId, emitter, true, null);
    }

    // 带 preRegisteredTaskId 的拒绝：拒绝前注销请求入口已注册的原始任务
    public void handleReject(String question, String conversationId, SseEmitter emitter, String preRegisteredTaskId) {
        handleReject(question, conversationId, emitter, true, preRegisteredTaskId);
    }

    // 拒绝但不写入对话历史：用于「问题实际未被处理」的拒绝场景（同会话并发、重复提交防护），
    // 避免把未回答的问题与"系统繁忙"假回答写进历史，污染后续多轮上下文
    public void handleRejectWithoutRecord(String question, String conversationId, SseEmitter emitter) {
        handleReject(question, conversationId, emitter, false, null);
    }

    // 带 preRegisteredTaskId 的拒绝（不写历史）
    public void handleRejectWithoutRecord(String question, String conversationId, SseEmitter emitter,
                                          String preRegisteredTaskId) {
        handleReject(question, conversationId, emitter, false, preRegisteredTaskId);
    }

    private void handleReject(String question, String conversationId, SseEmitter emitter, boolean record,
                              String preRegisteredTaskId) {
        // 注销请求入口已注册的原始任务：
        // StreamChatEventHandler 构造时已发送 META 并注册 taskId，且绑定了 emitter.onCompletion → cancelOnDisconnect。
        // 若不注销，下方 sendRejectEvents 的 sender.complete() 会触发取消回调，向历史写入
        // "对话被用户关闭"消息并重复发送取消事件（ghost 任务），污染会话上下文。
        if (StrUtil.isNotBlank(preRegisteredTaskId)) {
            taskManager.unregister(preRegisteredTaskId);
        }
        RejectedContext context = null;
        if (record) {
            try {
                context = recordRejectedConversation(question, conversationId, resolveUserId());
            } catch (Exception ex) {
                // 记录失败不能阻塞 emitter，否则前端永远收不到 DONE
                log.warn("记录 reject 会话失败，仍向前端发送 DONE", ex);
            }
        }
        sendRejectEvents(emitter, context, conversationId, preRegisteredTaskId);
    }

    // 记录被拒绝的对话: 保存用户问题和拒绝回复到消息记录，返回拒绝上下文
    private RejectedContext recordRejectedConversation(String question, String conversationId, String userId) {
        if (StrUtil.isBlank(question) || StrUtil.isBlank(userId)) {
            return null;
        }

        String actualConversationId;
        boolean isNewConversation;
        if (StrUtil.isBlank(conversationId)) {
            // 入参未带 conversationId：刚生成的雪花 ID 不可能命中已有会话，跳过 existence 查询
            actualConversationId = IdUtil.getSnowflakeNextIdStr();
            isNewConversation = true;
        } else {
            actualConversationId = conversationId;
            isNewConversation = conversationGroupService.findConversation(actualConversationId, userId) == null;
        }

        memoryService.append(actualConversationId, userId, ChatMessage.user(question));
        String messageId = memoryService.append(actualConversationId, userId, ChatMessage.assistant(REJECT_MESSAGE));

        String title = Strings.EMPTY;
        if (isNewConversation) {
            // append(USER) 内部会触发 conversationService.createOrUpdate（含 LLM 生成标题），此处回查拿到生成结果
            var conversation = conversationGroupService.findConversation(actualConversationId, userId);
            title = conversation != null ? conversation.getTitle() : Strings.EMPTY;
            if (StrUtil.isBlank(title)) {
                title = buildFallbackTitle(question);
            }
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();
        return new RejectedContext(actualConversationId, taskId, messageId, title);
    }

    // 根据用户问题截取生成兜底标题
    private String buildFallbackTitle(String question) {
        if (StrUtil.isBlank(question)) {
            return Strings.EMPTY;
        }
        int maxLen = memoryProperties.getTitleMaxLength() != null ? memoryProperties.getTitleMaxLength() : 30;
        String cleaned = question.trim();
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    // 向前端发送拒绝相关的 SSE 事件序列（META -> REJECT -> FINISH -> DONE）
    // rejectedContext 为 null（问题/用户为空，或 record=false 不落库）时同样发送完整序列：
    // 会话 ID 优先用 rejectedContext，其次用请求自带的 conversationId（拒绝事件挂到正确会话），
    // 都没有时兜底生成雪花 ID，避免前端只收到 DONE 而挂起等待 META
    private void sendRejectEvents(SseEmitter emitter, RejectedContext rejectedContext, String requestedConversationId,
                                  String preRegisteredTaskId) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        String conversationId = rejectedContext != null && StrUtil.isNotBlank(rejectedContext.conversationId)
                ? rejectedContext.conversationId
                : (StrUtil.isNotBlank(requestedConversationId)
                        ? requestedConversationId
                        : IdUtil.getSnowflakeNextIdStr());
        // 复用请求入口已发送过 META 的 taskId：客户端全程只看到一个 taskId，避免状态错乱；
        // 无 preRegisteredTaskId 时回退到 rejectedContext 或新生成
        String taskId = StrUtil.isNotBlank(preRegisteredTaskId)
                ? preRegisteredTaskId
                : (rejectedContext != null && StrUtil.isNotBlank(rejectedContext.taskId)
                        ? rejectedContext.taskId
                        : IdUtil.getSnowflakeNextIdStr());
        String messageId = rejectedContext != null ? rejectedContext.messageId : null;
        String title = rejectedContext != null ? rejectedContext.title : Strings.EMPTY;
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        sender.sendEvent(SSEEventType.REJECT.value(), new MessageDelta(RESPONSE_TYPE, REJECT_MESSAGE));
        sender.sendEvent(SSEEventType.FINISH.value(),
                new CompletionPayload(messageId != null ? String.valueOf(messageId) : null, title));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    // 解析当前用户 ID，优先从 UserContext 获取，回退到 Sa-Token 登录 ID
    private String resolveUserId() {
        String userId = UserContext.getUserId();
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        try {
            return StpUtil.getLoginIdAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record RejectedContext(String conversationId, String taskId, String messageId, String title) {
    }
}
