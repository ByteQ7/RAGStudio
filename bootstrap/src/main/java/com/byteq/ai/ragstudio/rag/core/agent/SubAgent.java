package com.byteq.ai.ragstudio.rag.core.agent;

import com.byteq.ai.ragstudio.infra.chat.StreamCallback;

import java.util.List;

/**
 * 子 Agent 接口（A2A 架构）
 * <p>
 * 多 Agent 架构中的专用 Agent，每个子 Agent 负责一个特定领域，
 * 拥有自己的工具集和 System Prompt。由 {@link OrchestratorAgent}
 * 通过 Task/Artifact 机制调度。
 * </p>
 *
 * <h3>生命周期</h3>
 * <ol>
 *   <li>Orchestrator 创建 {@link Task}，指定 targetAgent</li>
 *   <li>Orchestrator 调用目标 Agent 的 {@link #run(Task, AgentContext, StreamCallback)}</li>
 *   <li>Agent 执行并产出 {@link Artifact} 列表</li>
 *   <li>Orchestrator 收集 Artifacts，组装最终结果</li>
 * </ol>
 */
public interface SubAgent {

    /**
     * 获取 Agent 身份卡
     * <p>包含 Agent 名称、描述、能力标签、输入输出协议。</p>
     */
    AgentCard getCard();

    /**
     * 执行子 Agent 逻辑
     *
     * @param task     要执行的 Task（包含目标、输入参数等）
     * @param ctx      Agent 上下文
     * @param callback SSE 事件回调，Agent 的所有事件透传到该回调
     * @return 执行产出的 Artifact 列表
     */
    List<Artifact> run(Task task, AgentContext ctx, StreamCallback callback);
}