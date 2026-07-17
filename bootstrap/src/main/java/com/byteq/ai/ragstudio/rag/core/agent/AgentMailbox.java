package com.byteq.ai.ragstudio.rag.core.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 信箱
 * <p>
 * 每个 Agent 拥有一个信箱，用于接收 {@link Task}。
 * {@link OrchestratorAgent} 通过 Agent 名称查找信箱并投递 Task。
 * 信箱采用队列模式，支持同步提交和异步获取。
 * </p>
 */
public class AgentMailbox {

    private final String agentName;
    private final CopyOnWriteArrayList<Task> inbox = new CopyOnWriteArrayList<>();

    public AgentMailbox(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentName() { return agentName; }

    /** 投递 Task 到信箱 */
    public void submit(Task task) {
        inbox.add(task);
    }

    /** 取出下一个待处理的 Task（FIFO），没有则返回 null */
    public Task poll() {
        return inbox.isEmpty() ? null : inbox.remove(0);
    }

    /** 获取所有待处理 Task 数量 */
    public int pendingCount() {
        return inbox.size();
    }

    /** 获取信箱中所有 Task 的不可变快照 */
    public List<Task> snapshot() {
        return List.copyOf(inbox);
    }
}
