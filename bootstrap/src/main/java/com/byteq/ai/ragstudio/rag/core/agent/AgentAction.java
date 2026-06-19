package com.byteq.ai.ragstudio.rag.core.agent;

/**
 * Agent 动作类型枚举
 * <p>
 * 定义 ReACT Agent 循环中 Planning 模块可能产出的动作：
 * <ul>
 *   <li>{@link #TOOL_CALL} — 调用指定工具，随后等待 Observation</li>
 *   <li>{@link #FINISH} — 推理完成，输出最终回答</li>
 *   <li>{@link #ERROR} — 解析或执行过程中出现异常，需兜底处理</li>
 * </ul>
 */
public enum AgentAction {

    /** 调用工具 */
    TOOL_CALL,

    /** 完成推理，给出最终答案 */
    FINISH,

    /** 异常/错误（解析失败、超时等降级场景） */
    ERROR
}
