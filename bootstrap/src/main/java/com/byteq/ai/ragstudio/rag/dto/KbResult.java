package com.byteq.ai.ragstudio.rag.dto;

/**
 * KB 检索结果记录
 * <p>
 * 表示从知识库（Knowledge Base）中检索到的结果，包含分组后的上下文文本。
 * </p>
 *
 * @param groupedContext 分组后的上下文文本，已格式化为适合 Prompt 拼接的字符串
 */
public record KbResult(String groupedContext) {
    /**
     * 返回一个空的检索结果
     * <p>
     * 当没有任何检索结果时使用，避免返回 null。
     * </p>
     *
     * @return 空的 KbResult 实例
     */
    public static KbResult empty() {
        return new KbResult("");
    }
}
