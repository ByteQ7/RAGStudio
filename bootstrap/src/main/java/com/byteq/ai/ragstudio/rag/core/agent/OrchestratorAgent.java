package com.byteq.ai.ragstudio.rag.core.agent;

import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主 Agent（Orchestrator）— A2A 架构
 * <p>
 * 负责任务路由和结果汇总：
 * </p>
 * <ol>
 *   <li>接收用户问题，创建初始 Task</li>
 *   <li>根据 Task 类型路由到目标 {@link SubAgent}</li>
 *   <li>收集 Agent 产出的 {@link Artifact}</li>
 *   <li>需要时创建子 Task（如标题生成）</li>
 *   <li>汇总所有 Artifact 返回最终结果</li>
 * </ol>
 *
 * <h3>Agent 注册</h3>
 * 所有可用 Agent 通过 {@link #register(SubAgent)} 注册，
 * 系统启动时由 {@code StreamChatPipeline} 完成注册。
 */
@Slf4j
public class OrchestratorAgent {

    /** Agent 名称 → Agent 实例 */
    private final Map<String, SubAgent> agentMap = new ConcurrentHashMap<>();

    /** Agent 名称 → 信箱 */
    private final Map<String, AgentMailbox> mailboxes = new ConcurrentHashMap<>();

    public OrchestratorAgent() {
    }

    /**
     * 注册一个子 Agent
     */
    public void register(SubAgent agent) {
        String name = agent.getCard().name();
        agentMap.put(name, agent);
        mailboxes.put(name, new AgentMailbox(name));
        log.info("Orchestrator: 注册 Agent [{}] - {}", name, agent.getCard().description());
    }

    /**
     * 获取所有已注册 Agent 的卡片
     */
    public List<AgentCard> getAgentCards() {
        return agentMap.values().stream().map(SubAgent::getCard).toList();
    }

    /**
     * 根据用户问题路由并执行
     *
     * @param question 用户问题
     * @param ctx      Agent 上下文
     * @param callback SSE 回调
     * @return 所有 Artifact 列表（包含各 Agent 的产出）
     */
    public List<Artifact> run(String question, AgentContext ctx, StreamCallback callback) {
        List<Artifact> allArtifacts = new ArrayList<>();

        // 决定使用哪个 Agent
        String targetAgent = resolveAgent(question);
        log.info("Orchestrator: 路由决策 → [{}]", targetAgent);

        // 创建并投递 Task
        Map<String, Object> taskInput = new HashMap<>();
        taskInput.put("question", question);
        Task task = new Task(targetAgent, taskInput);

        // 执行 Task
        SubAgent agent = agentMap.get(targetAgent);
        if (agent == null) {
            log.warn("Orchestrator: Agent [{}] 未注册，回退到第一个可用 Agent", targetAgent);
            agent = agentMap.values().iterator().next();
        }

        task.setStatus(Task.Status.RUNNING);
        try {
            List<Artifact> artifacts = agent.run(task, ctx, callback);
            task.setStatus(Task.Status.COMPLETED);
            allArtifacts.addAll(artifacts);

            // 如果 Agent 产出了 answer 类型的 Artifact，尝试生成标题
            boolean hasAnswer = artifacts.stream().anyMatch(a -> "answer".equals(a.type()));
            if (hasAnswer && agentMap.containsKey("title")) {
                try {
                    Map<String, Object> titleInput = new HashMap<>();
                    titleInput.put("question", question);
                    titleInput.put("answer", artifacts.stream()
                            .filter(a -> "answer".equals(a.type()))
                            .findFirst().map(Artifact::contentAsString).orElse(""));
                    Task titleTask = new Task("title", titleInput);
                    SubAgent titleAgent = agentMap.get("title");
                    List<Artifact> titleArtifacts = titleAgent.run(titleTask, ctx, callback);
                    allArtifacts.addAll(titleArtifacts);
                } catch (Exception e) {
                    log.warn("Orchestrator: 标题生成失败，继续", e);
                }
            }

        } catch (Exception e) {
            task.setStatus(Task.Status.FAILED);
            task.setErrorMessage(e.getMessage());
            log.error("Orchestrator: Agent [{}] 执行失败", targetAgent, e);
            throw e;
        }

        return allArtifacts;
    }

    /**
     * 路由决策
     * <p>单 Agent 时直接返回，多 Agent 时选择第一个支持该问题的 Agent</p>
     */
    private String resolveAgent(String question) {
        if (agentMap.size() == 1) {
            return agentMap.keySet().iterator().next();
        }
        // 多 Agent 时返回第一个可用 Agent（后续可接入 LLM 路由）
        return agentMap.keySet().iterator().next();
    }
}
