package com.byteq.ai.ragstudio.ingestion.prompt;

import com.byteq.ai.ragstudio.ingestion.domain.enums.ChunkEnrichType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 分块富化提示词管理器
 * <p>
 * 维护各富化类型（关键词提取、摘要生成、元数据提取）的默认系统提示词，
 * 供 EnricherNode 在对文本块进行 AI 富化时调用大语言模型使用。
 * </p>
 */
public final class EnricherPromptManager {

    private static final Map<ChunkEnrichType, String> DEFAULT_SYSTEM_PROMPTS = new EnumMap<>(ChunkEnrichType.class);

    static {
        DEFAULT_SYSTEM_PROMPTS.put(ChunkEnrichType.KEYWORDS, """
                从文本片段中提取 3-8 个关键词/短语。
                输出格式：JSON 数组，如 ["关键词1", "关键词2"]
                只输出 JSON，不要其他内容。
                """);

        DEFAULT_SYSTEM_PROMPTS.put(ChunkEnrichType.SUMMARY, """
                请用 1-3 句话对文本片段进行摘要，保持关键信息完整。
                直接输出摘要文本，不要添加标题或解释。
                """);

        DEFAULT_SYSTEM_PROMPTS.put(ChunkEnrichType.METADATA, """
                从文本片段中抽取可结构化的信息，输出 JSON 对象。
                只输出 JSON，不要其他内容。
                """);
    }

    private EnricherPromptManager() {
    }

    /**
     * 获取指定富化类型的默认系统提示词
     *
     * @param type 分块富化类型枚举
     * @return 对应的默认系统提示词，如果类型未注册则返回 null
     */
    public static String systemPrompt(ChunkEnrichType type) {
        return DEFAULT_SYSTEM_PROMPTS.get(type);
    }
}
