package com.byteq.ai.ragstudio.rag.core.harness.context;

/**
 * 动态上下文块（Harness · 上下文子模块）
 * <p>
 * 由 Harness 在 Agent 循环期间动态注册/失效的上下文片段，每轮模型调用前由
 * {@link DynamicContextMiddleware} 按 {@code active} 状态拼装进 system message。
 * <p>
 * 典型用途：工作流候选清单（召回时注入）→ 选中某个工作流后失效（下一轮迭代起不再出现），
 * 避免候选列表长期占用上下文。
 *
 * @param id       块唯一标识（如 {@code workflow:candidates}）
 * @param content  块文本内容（拼入 system message）
 * @param priority 排序优先级（值越小越靠前）
 */
public record ContextBlock(String id, String content, int priority) {

    public ContextBlock {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ContextBlock id 不能为空");
        }
    }

    public static ContextBlock of(String id, String content) {
        return new ContextBlock(id, content, 100);
    }
}
