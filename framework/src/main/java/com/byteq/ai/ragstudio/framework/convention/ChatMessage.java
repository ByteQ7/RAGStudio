package com.byteq.ai.ragstudio.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话消息实体
 *
 * <p>
 * 用于统一抽象「大模型对话」中的一条消息，包含角色和消息内容：
 * <ul>
 *   <li>{@link Role#SYSTEM}：系统提示词，用于为大模型设定行为、规则</li>
 *   <li>{@link Role#USER}：用户输入消息</li>
 *   <li>{@link Role#ASSISTANT}：大模型（助手）回复内容</li>
 *   <li>{@link Role#OBSERVATION}：ReACT 循环中的 Observation（工具执行结果，如知识库检索、API 调用返回），LLM 应将其视为系统返回而非用户发言</li>
 * </ul>
 * 该结构适合在不同模型/厂商之间做一层通用抽象
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息角色类型
     */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        /** ReACT 循环中的 Observation（工具执行结果），LLM 应视为系统返回而非用户发言 */
        OBSERVATION;

        public static Role fromString(String value) {
            if ("tool".equalsIgnoreCase(value)) {
                return OBSERVATION;
            }
            for (Role role : Role.values()) {
                if (role.name().equalsIgnoreCase(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("无效的角色类型: " + value);
        }
    }

    /**
     * 当前消息的角色（系统 / 用户 / 助手）
     */
    private Role role;

    /**
     * 消息的具体文本内容
     */
    private String content;

    /**
     * 图片 URL 列表（仅 USER 角色携带，用于多模态识别）
     */
    private List<String> imageUrls;

    /**
     * 深度思考内容（仅 ASSISTANT 角色可能携带）
     */
    private String thinkingContent;

    /**
     * 深度思考级别（0-100）
     */
    private Integer thinkingLevel;

    /**
     * 深度思考耗时（秒，仅 ASSISTANT 角色可能携带）
     */
    private Integer thinkingDuration;

    /** Agent 推理步骤 JSON */
    private String agentSteps;

    /** 引用溯源 JSON */
    private String citations;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 创建一条系统消息
     *
     * @param content 系统提示词内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#SYSTEM}
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    /**
     * 创建一条用户消息
     *
     * @param content 用户输入内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#USER}
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    /**
     * 创建一条助手消息
     *
     * @param content 助手回复内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    /**
     * 创建一条 Observation（工具执行结果）消息
     *
     * @param content 工具返回内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#OBSERVATION}
     */
    public static ChatMessage observation(String content) {
        return new ChatMessage(Role.OBSERVATION, content);
    }

    /**
     * 创建一条带思考内容的助手消息
     *
     * @param content         助手回复内容
     * @param thinkingContent 深度思考内容
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content, String thinkingContent) {
        return assistant(content, thinkingContent, null);
    }

    /**
     * 创建一条带思考内容和思考耗时的助手消息
     *
     * @param content          助手回复内容
     * @param thinkingContent  深度思考内容
     * @param thinkingDuration 深度思考耗时（秒）
     * @return 封装好的 {@link ChatMessage} 对象，角色为 {@link Role#ASSISTANT}
     */
    public static ChatMessage assistant(String content, String thinkingContent, Integer thinkingDuration) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, content);
        message.setThinkingContent(thinkingContent);
        message.setThinkingDuration(thinkingDuration);
        return message;
    }

    public static ChatMessage assistant(String content, String thinkingContent, Integer thinkingDuration, String agentSteps) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, content);
        message.setThinkingContent(thinkingContent);
        message.setThinkingDuration(thinkingDuration);
        message.setAgentSteps(agentSteps);
        return message;
    }

    public static ChatMessage assistant(String content, String thinkingContent, Integer thinkingDuration, String agentSteps, String citations) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, content);
        message.setThinkingContent(thinkingContent);
        message.setThinkingDuration(thinkingDuration);
        message.setAgentSteps(agentSteps);
        message.setCitations(citations);
        return message;
    }
}
