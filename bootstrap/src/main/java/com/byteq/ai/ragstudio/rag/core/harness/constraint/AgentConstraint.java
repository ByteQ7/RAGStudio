package com.byteq.ai.ragstudio.rag.core.harness.constraint;

import com.byteq.ai.ragstudio.rag.core.harness.context.AgentContext;

import java.util.List;

/**
 * Agent 行为约束（Harness · 约束子模块）
 * <p>
 * 约束是拼入 system prompt 的强指令（如"必须检索知识库"、"多轮对话注意点"），
 * 与静态模板的区别是：约束是否生效取决于运行时上下文（历史长度、KB 相关性、
 * 是否启用 rag_search 等），且可由 Harness 统一编排、按需启停。
 * <p>
 * 实现类为 Spring Bean，按 {@link #order()} 升序注入 system prompt。
 */
public interface AgentConstraint {

    /** 约束唯一标识（诊断/测试用） */
    String id();

    /** 排序（值越小越靠前，默认 100） */
    default int order() {
        return 100;
    }

    /** 当前上下文下是否生效 */
    boolean isActive(AgentContext ctx, boolean hasRagSearch);

    /** 渲染约束文本（不生效时不会被调用） */
    String render();
}
