package com.byteq.ai.ragstudio.rag.core.harness.tool;

import com.byteq.ai.ragstudio.rag.config.ObservationMaskProperties;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.harness.observation.ObservationReaderTool;
import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolExecutor;
import com.byteq.ai.ragstudio.rag.core.mcp.McpToolRegistry;
import com.byteq.ai.ragstudio.rag.core.retrieve.RetrievalEngine;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDefinition;
import com.byteq.ai.ragstudio.rag.core.skill.SkillLoader;
import com.byteq.ai.ragstudio.rag.core.skill.SkillTool;
import com.byteq.ai.ragstudio.rag.core.skill.ToolReaderTool;
import com.byteq.ai.ragstudio.rag.core.skill.WebSearchTool;
import com.byteq.ai.ragstudio.rag.core.skill.SandboxExecutor;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;
import com.byteq.ai.ragstudio.rag.core.tool.ToolNameUtil;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具注册组装器（Harness · 工具子模块）
 * <p>
 * 统一把各类工具注册进 AgentScope {@link Toolkit}，是工具可见性的唯一入口：
 * <ol>
 *   <li><b>内置工具</b>：rag_search（绑定本次请求 KB/改写上下文）、time_now、tool_reader、
 *       observation_reader（观察掩码开启时，回读被压缩的工具结果）</li>
 *   <li><b>MCP 工具</b>：全量注册（由模型自主选择；后续可接入 ToolRetriever 预筛）</li>
 *   <li><b>SKILL 工具</b>：仅注册有执行配置的技能；纯知识型技能通过 tool_reader 激活</li>
 *   <li><b>扩展工具</b>：{@link HarnessToolProvider}（工作流等业务模块贡献）</li>
 * </ol>
 * 所有工具名经 {@link ToolNameUtil} 规范化并消解碰撞，原始名→规范化名写入
 * {@link ToolAssemblyContext#getToolNameMapping()}。
 */
@Slf4j
@Component
public class ToolRegistryAssembler {

    private final RetrievalEngine retrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final ObservationMaskProperties observationMaskProperties;
    private final McpToolRegistry mcpToolRegistry;
    private final SkillLoader skillLoader;
    private final List<HarnessToolProvider> toolProviders;

    public ToolRegistryAssembler(RetrievalEngine retrievalEngine,
                                 SearchChannelProperties searchProperties,
                                 ObservationMaskProperties observationMaskProperties,
                                 McpToolRegistry mcpToolRegistry,
                                 SkillLoader skillLoader,
                                 List<HarnessToolProvider> toolProviders) {
        this.retrievalEngine = retrievalEngine;
        this.searchProperties = searchProperties;
        this.observationMaskProperties = observationMaskProperties;
        this.mcpToolRegistry = mcpToolRegistry;
        this.skillLoader = skillLoader;
        this.toolProviders = toolProviders != null ? toolProviders : List.of();
    }

    /**
     * 组装本次请求的 AgentScope Toolkit
     *
     * @param ctx       Agent 上下文（提供 KB 列表、改写查询等）
     * @param assembly  工具组装运行时状态（工具名/回调）
     * @param sandboxExecutor        SKILL 沙箱执行器
     * @param sandboxEnabled         沙箱总开关
     * @param sandboxNetworkEnabled  沙箱默认网络开关
     * @param allowedCommandPrefixes command 类型命令前缀白名单
     */
    public Toolkit assemble(AgentContext ctx, ToolAssemblyContext assembly,
                            SandboxExecutor sandboxExecutor, boolean sandboxEnabled,
                            boolean sandboxNetworkEnabled, List<String> allowedCommandPrefixes) {
        Toolkit toolkit = new Toolkit();

        // 1. rag_search（引用溯源：chunks 与上下文 [^chunk_N] 编号按同一顺序追加）
        RagSearchTool ragTool = new RagSearchTool(retrievalEngine, searchProperties,
                ctx.getKnowledgeBaseIds(), ctx.getKbSummaryText(),
                ctx.getQuestion(), ctx.getRewrittenQuery(), ctx.getSubQuestions());
        ragTool.setChunksConsumer(chunks -> {
            if (assembly.getChunksConsumer() != null) {
                assembly.getChunksConsumer().accept(chunks);
            }
        });
        ragTool.setCitationStartIndexSupplier(() -> assembly.getCitationStartIndexSupplier() != null
                ? assembly.getCitationStartIndexSupplier().getAsInt() : 0);
        register(toolkit, assembly, ragTool);

        // 2. 内置 time_now
        register(toolkit, assembly, new TimeTool());

        // 3. MCP 工具（全部注册，运行时由模型自主选择）
        for (McpToolExecutor executor : mcpToolRegistry.listAllExecutors()) {
            register(toolkit, assembly, new McpToolAdapter(executor));
        }

        // 4. tool_reader：运行时发现 MCP + SKILL 工具（展示规范化名，与模型可见名称一致）
        register(toolkit, assembly, new ToolReaderTool(skillLoader, mcpToolRegistry,
                assembly.getToolNameMapping()));

        // 5. observation_reader：观察掩码开启时，凭句柄回读被压缩的工具结果
        if (assembly.getObservationStore() != null) {
            register(toolkit, assembly,
                    new ObservationReaderTool(assembly.getObservationStore(), observationMaskProperties));
        }

        // 6. SKILL 工具（仅注册有执行配置的技能；纯知识型技能通过 tool_reader 激活）
        List<SkillDefinition> skills = skillLoader.getAllSkills();
        int executableSkills = 0;
        for (SkillDefinition def : skills) {
            if (!def.isExecutable()) {
                log.debug("SKILL [{}] 为纯知识型技能，不注册为可调用工具", def.getName());
                continue;
            }
            executableSkills++;
            SkillTool skillTool = new SkillTool(def, sandboxExecutor,
                    sandboxEnabled, sandboxNetworkEnabled, allowedCommandPrefixes);
            // 网络搜索技能包装为引用溯源工具：结果与知识库 Chunk 共用 [^chunk_N] 编号进入 citations
            Tool tool = isWebSearchSkill(def)
                    ? new WebSearchTool(skillTool,
                            chunks -> {
                                if (assembly.getChunksConsumer() != null) {
                                    assembly.getChunksConsumer().accept(chunks);
                                }
                            },
                            () -> assembly.getCitationStartIndexSupplier() != null
                                    ? assembly.getCitationStartIndexSupplier().getAsInt() : 0)
                    : skillTool;
            register(toolkit, assembly, tool);
        }

        // 7. 扩展工具（HarnessToolProvider：工作流等）
        int providedCount = 0;
        for (HarnessToolProvider provider : toolProviders) {
            try {
                List<Tool> provided = provider.provideTools(ctx);
                if (provided == null) {
                    continue;
                }
                for (Tool tool : provided) {
                    register(toolkit, assembly, tool);
                    providedCount++;
                }
            } catch (Exception e) {
                log.warn("HarnessToolProvider [{}] 提供工具失败，跳过: {}",
                        provider.getClass().getSimpleName(), e.getMessage());
            }
        }

        // 内置工具 = rag_search + time_now + tool_reader（+ observation_reader，掩码开启时）
        int builtinCount = 3 + (assembly.getObservationStore() != null ? 1 : 0);
        log.info("AgentScope 工具注册: MCP={}, SKILL={}(可执行 {}), 扩展={}, 内置={}, 总计={}",
                mcpToolRegistry.size(), skills.size(), executableSkills, providedCount, builtinCount,
                assembly.getToolNames().size());
        return toolkit;
    }

    /** 是否为网络搜索类 SKILL（结果接入 WEB 引用溯源） */
    private boolean isWebSearchSkill(SkillDefinition def) {
        return "web-search".equalsIgnoreCase(def.getName());
    }

    private void register(Toolkit toolkit, ToolAssemblyContext assembly, Tool tool) {
        try {
            ProjectToolAdapter adapter = new ProjectToolAdapter(tool, result -> {
                if (assembly.getResultConsumer() != null) {
                    assembly.getResultConsumer().accept(result);
                }
            });
            String finalName = resolveToolName(tool.name(), assembly);
            adapter.setExposedName(finalName);
            toolkit.registerTool(adapter);
            assembly.getToolNames().add(finalName);
        } catch (Exception e) {
            log.warn("工具 [{}] 注册失败，跳过: {}", tool.name(), e.getMessage());
        }
    }

    /**
     * 规范化工具名并消解碰撞：
     * DeepSeek 等厂商严格要求函数名匹配 ^[a-zA-Z0-9_-]+$ 且 ≤64 字符，
     * MCP/SKILL 工具名可能含中文、点号、冒号等非法字符，直接透传会 400；
     * 不同原始名清洗后可能重名（如 a.b 与 a-b 均变为 a_b），追加数字后缀消解。
     * 原始名 → 规范化名 的映射记录在 assembly，供 tool_reader 等展示一致性使用。
     */
    private String resolveToolName(String rawName, ToolAssemblyContext assembly) {
        String base = ProjectToolAdapter.sanitizeToolName(rawName);
        String finalName = base;
        if (!assembly.getUsedToolNames().add(base)) {
            int counter = 1;
            while (true) {
                String suffix = "_" + (counter++);
                int keep = Math.max(1, ToolNameUtil.MAX_TOOL_NAME_LENGTH - suffix.length());
                finalName = base.length() > keep ? base.substring(0, keep) : base;
                finalName += suffix;
                if (assembly.getUsedToolNames().add(finalName)) {
                    break;
                }
            }
        }
        assembly.getToolNameMapping().put(rawName, finalName);
        return finalName;
    }
}
