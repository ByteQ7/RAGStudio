package com.byteq.ai.ragstudio.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.byteq.ai.ragstudio.rag.core.agent.AgentStep;
import com.byteq.ai.ragstudio.rag.dao.entity.ConversationDO;
import com.byteq.ai.ragstudio.rag.dto.AgentStepPayload;
import com.byteq.ai.ragstudio.rag.dto.CompletionPayload;
import com.byteq.ai.ragstudio.rag.dto.MessageDelta;
import com.byteq.ai.ragstudio.rag.dto.MetaPayload;
import com.byteq.ai.ragstudio.rag.enums.SSEEventType;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.web.SseEmitterSender;
import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.core.memory.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import com.byteq.ai.ragstudio.rag.service.ConversationGroupService;

@Slf4j
public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private long thinkingStartMs;
    private int thinkingDurationSeconds;
    private String agentStepsJson;
    private String citationsJson;
    private int thinkingLevel;

    public void setThinkingLevel(int level) { this.thinkingLevel = level; }

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();

        // 直接使用已计算好的消息块大小
        this.messageChunkSize = params.getMessageChunkSize();
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    /**
     * 初始化：发送元数据事件并注册任务
     */
    private void initialize() {
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 判断是否需要发送标题
     */
    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     */
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        try {
            String displayContent;
            if (StrUtil.isNotBlank(content)) {
                displayContent = content + "\n\n---\n> **对话被用户关闭** ❗";
            } else {
                displayContent = "> **对话被用户关闭** ❗";
            }
            String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
            ChatMessage message = ChatMessage.assistant(displayContent, thinkingContent,
                    resolveThinkingDuration(), agentStepsJson, citationsJson);
            message.setThinkingLevel(thinkingLevel);
            messageId = memoryService.append(conversationId, userId, message);
        } catch (Exception e) {
            log.error("取消时持久化消息失败，conversationId：{}", conversationId, e);
        }
        String title = resolveTitleForEvent();
        return new CompletionPayload(messageId, title);
    }

    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isEmpty(chunk)) {
            return;
        }
        if (thinkingStartMs > 0 && thinkingDurationSeconds == 0) {
            thinkingDurationSeconds = Math.max(1, Math.round((System.currentTimeMillis() - thinkingStartMs) / 1000.0f));
        }
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isEmpty(chunk)) {
            return;
        }
        if (thinkingStartMs == 0) {
            thinkingStartMs = System.currentTimeMillis();
        }
        thinking.append(chunk);
        sendChunked(TYPE_THINK, chunk);
    }

    @Override
    public void onAgentStep(Object step) {
        if (step instanceof AgentStep agentStep) {
            AgentStepPayload payload = AgentStepPayload.from(agentStep);
            sender.sendEvent(SSEEventType.AGENT_STEP.value(), payload);
            log.debug("推送 Agent 步骤 SSE: iteration={}, action={}, toolName={}",
                    agentStep.getIteration(), agentStep.getAction(), agentStep.getToolName());
        }
    }

    @Override
    public void onAgentStepsComplete(String agentStepsJson) {
        this.agentStepsJson = agentStepsJson;
    }

    @Override
    public void onCitation(String citations) {
        this.citationsJson = citations;
        if (StrUtil.isNotBlank(citations)) {
            sender.sendEvent(SSEEventType.CITATION.value(), citations);
        }
    }

    /**
     * 将 Agent 推理步骤嵌入到 assistant 内容中
     * <p>解析 agentStepsJson 中的步骤信息，格式化为可读的推理链追加到消息内容尾部。</p>
     *
     * @param content        原始 assistant 内容
     * @param agentStepsJson Agent 步骤 JSON 数组字符串
     * @return 嵌入推理链后的内容
     */
    private String enrichWithAgentTrace(String content, String agentStepsJson) {
        if (StrUtil.isBlank(agentStepsJson)) {
            return content;
        }
        try {
            JsonArray steps = JsonParser.parseString(agentStepsJson).getAsJsonArray();
            if (steps.isEmpty()) {
                return content;
            }
            StringBuilder sb = new StringBuilder(content);
            sb.append("\n\n---\n").append("🧠 **Agent 推理过程**\n\n");
            for (int i = 0; i < steps.size(); i++) {
                JsonObject s = steps.get(i).getAsJsonObject();
                int iteration = s.get("iteration").getAsInt();
                sb.append("> **步骤 ").append(iteration + 1).append("**");

                String plan = getJsonString(s, "plan");
                if (plan != null) {
                    sb.append("\n> 📋 ").append(plan.replace("\n", "\n> "));
                }
                String thought = getJsonString(s, "thought");
                if (thought != null) {
                    sb.append("\n> 🤔 ").append(thought.replace("\n", "\n> "));
                }

                String action = s.has("action") && !s.get("action").isJsonNull() ? s.get("action").getAsString() : null;
                if ("TOOL_CALL".equals(action)) {
                    String toolName = getJsonString(s, "toolName");
                    if (toolName != null) {
                        sb.append("\n> 🔧 调用工具: `").append(toolName).append("`");
                    }
                    String observation = getJsonString(s, "observation");
                    if (observation != null) {
                        String truncated = observation.length() > 200 ? observation.substring(0, 200) + "…" : observation;
                        sb.append("\n> 📝 ").append(truncated.replace("\n", "\n> "));
                    }
                } else if ("FINISH".equals(action)) {
                    sb.append("\n> ✅ 完成回答");
                } else if ("ERROR".equals(action)) {
                    sb.append("\n> ❌ 执行出错");
                }

                if (s.has("durationMs") && !s.get("durationMs").isJsonNull()) {
                    long dur = s.get("durationMs").getAsLong();
                    if (dur > 0) {
                        sb.append(" （").append(dur).append("ms）");
                    }
                }
                sb.append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("嵌入 Agent 推理链失败", e);
            return content;
        }
    }

    /**
     * 安全获取 JSON 字符串字段
     */
    private static String getJsonString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        String val = obj.get(key).getAsString();
        return val.isEmpty() ? null : val;
    }

    @Override
    public void onComplete() {
        log.warn("onComplete called, thinkingLevel={}", thinkingLevel);
        if (taskManager.isCancelled(taskId)) {
            // content 已在 buildCompletionPayloadOnCancel 中持久化，不再重复保存
            sender.sendEvent(SSEEventType.DONE.value(), "");
            return;
        }
        String messageId = null;
        try {
            String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
            ChatMessage message = ChatMessage.assistant(answer.toString(), thinkingContent,
                    resolveThinkingDuration(), agentStepsJson, citationsJson);
            message.setThinkingLevel(thinkingLevel);
            messageId = memoryService.append(conversationId, userId, message);
        } catch (Exception e) {
            log.error("对话完成时持久化消息失败，conversationId：{}", conversationId, e);
        }
        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    // 按 Unicode 码点将内容分块发送，确保多字节字符（如 emoji）不会被截断
    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    /**
     * 获取已累积的完整回答内容（在 onComplete 前调用）
     */
    public String getAnswerString() {
        return answer.toString();
    }

    // 解析深度思考耗时，未记录则返回 null
    private Integer resolveThinkingDuration() {
        return thinkingDurationSeconds > 0 ? thinkingDurationSeconds : null;
    }

    // 获取会话标题用于完成事件推送，新对话无标题时返回默认值
    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
