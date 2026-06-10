package com.byteq.ai.ragstudio.infra.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 模型能力枚举
 * <p>
 * 定义了 AI 模型支持的各种能力类型，用于统一标识模型的功能特性。
 * 在模型路由、URL 解析和端点选择等场景中，通过该枚举区分不同模型能力，
 * 以便从配置中获取对应的端点路径和执行相应的调用逻辑。
 * </p>
 *
 * <p>当前支持的能力类型包括：</p>
 * <ul>
 *   <li>{@link #CHAT} - 聊天对话能力，用于与用户进行多轮自然语言交互</li>
 *   <li>{@link #EMBEDDING} - 向量嵌入能力，将文本转换为向量用于语义检索</li>
 *   <li>{@link #RERANK} - 重排序能力，对检索结果进行相关性重新排序</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ModelCapability {

    /**
     * 聊天对话能力
     * <p>
     * 支持与用户进行多轮自然语言对话交互，是 LLM 最核心的能力。
     * 对应模型提供商的 chat/completions 类 API 端点。
     * </p>
     */
    CHAT("Chat"),

    /**
     * 向量嵌入能力
     * <p>
     * 将文本转换为高维向量表示，用于语义搜索、相似度计算和知识检索。
     * 对应模型提供商的 embeddings 类 API 端点。
     * </p>
     */
    EMBEDDING("Embedding"),

    /**
     * 重排序能力
     * <p>
     * 对检索结果进行相关性重新排序，常用于 RAG（检索增强生成）场景中
     * 对召回结果进行精排，提高最终输出的相关性。
     * 对应模型提供商的 rerank 类 API 端点。
     * </p>
     */
    RERANK("Rerank");

    /**
     * 能力的显示名称
     */
    private final String displayName;
}
