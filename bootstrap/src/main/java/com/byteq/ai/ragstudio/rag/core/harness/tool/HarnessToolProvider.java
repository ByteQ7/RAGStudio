package com.byteq.ai.ragstudio.rag.core.harness.tool;

import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;
import com.byteq.ai.ragstudio.rag.core.tool.Tool;

import java.util.List;

/**
 * Harness 工具提供者（扩展点）
 * <p>
 * 除内置工具（rag_search / time_now / tool_reader）、MCP 工具与 SKILL 工具外，
 * 需要向 Agent 注册工具的模块实现本接口（如工作流的 workflow_extract / workflow_save /
 * workflow_use），由 {@link ToolRegistryAssembler} 统一注册。
 * <p>
 * 实现类应为 Spring Bean，注册顺序无要求；工具名冲突由组装器统一消解。
 */
public interface HarnessToolProvider {

    /**
     * 提供本次请求需要注册的工具
     *
     * @param ctx 当前 Agent 上下文（提供者可按需裁剪工具集）
     * @return 工具列表；返回空列表表示本次不注册
     */
    List<Tool> provideTools(AgentContext ctx);
}
