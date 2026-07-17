package com.byteq.ai.ragstudio.rag.core.agent;

import com.byteq.ai.ragstudio.infra.chat.StreamCallback;
import com.byteq.ai.ragstudio.rag.core.agent.McpToolAdapter;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDefinition;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.core.skill.SkillTool;
import com.byteq.ai.ragstudio.rag.core.skill.SandboxExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具执行 Agent（A2A 架构）
 * <p>
 * 专门执行 MCP 工具和用户自定义 SKILL 工具，不含知识库检索能力。
 * 适用于"搜索网络"、"执行命令"、"查询系统信息"等纯工具调用场景。
 * </p>
 *
 * <h3>输入 Task 参数</h3>
 * <ul>
 *   <li>{@code tool} (String) — 要调用的工具名称</li>
 *   <li>{@code params} (Map) — 工具参数</li>
 * </ul>
 *
 * <h3>产出 Artifact</h3>
 * <ul>
 *   <li>{@code type="tool_result"} — 工具执行结果文本</li>
 * </ul>
 */
@Slf4j
public class ToolSubAgent implements SubAgent {

    private static final AgentCard CARD = new AgentCard(
            "tool",
            "执行 MCP 外部工具和用户自定义 SKILL。适用于搜索网络、执行命令、查询系统信息等场景。不处理知识库检索。",
            List.of("mcp_tools", "skill_tools", "time_tool")
    );

    private final McpToolRegistry mcpToolRegistry;
    private final SkillLoader skillLoader;
    private final okhttp3.OkHttpClient syncHttpClient;
    private final SandboxExecutor sandboxExecutor;

    public ToolSubAgent(McpToolRegistry mcpToolRegistry, SkillLoader skillLoader,
                        okhttp3.OkHttpClient syncHttpClient, SandboxExecutor sandboxExecutor) {
        this.mcpToolRegistry = mcpToolRegistry;
        this.skillLoader = skillLoader;
        this.syncHttpClient = syncHttpClient;
        this.sandboxExecutor = sandboxExecutor;
    }

    @Override
    public AgentCard getCard() { return CARD; }

    @Override
    public List<Artifact> run(Task task, AgentContext ctx, StreamCallback callback) {
        // 构建工具注册表
        ToolRegistry registry = buildToolRegistry();

        String toolName = task.getInputString("tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = task.getInputAs("params");
        if (params == null) params = Map.of();

        // 查找并执行工具
        if (toolName == null || !registry.contains(toolName)) {
            // 无指定工具时返回可用工具列表
            List<String> availableTools = registry.listNames();
            return List.of(new Artifact(task.getId(), CARD.name(), "tool_result",
                    "可用工具: " + String.join(", ", availableTools)));
        }

        try {
            ToolResult result = registry.execute(toolName, params);
            return List.of(new Artifact(task.getId(), CARD.name(), "tool_result",
                    result.toObservation()));
        } catch (Exception e) {
            log.warn("Tool Agent 执行 [{}] 失败", toolName, e);
            return List.of(new Artifact(task.getId(), CARD.name(), "tool_result",
                    "工具执行失败: " + e.getMessage()));
        }
    }

    private ToolRegistry buildToolRegistry() {
        ToolRegistry registry = new ToolRegistry();

        // MCP 工具
        for (McpToolExecutor executor : mcpToolRegistry.listAllExecutors()) {
            registry.register(new McpToolAdapter(executor));
        }

        // 时间工具
        registry.register(new TimeTool());

        // 用户自定义 SKILL
        List<SkillDefinition> skills = skillLoader.getAllSkills();
        for (SkillDefinition def : skills) {
            registry.register(new SkillTool(def, syncHttpClient, sandboxExecutor));
        }

        log.info("Tool Agent 工具: MCP={}, SKILL={}, 内置=1, 总计={}",
                mcpToolRegistry.size(), skills.size(), registry.size());
        return registry;
    }
}
