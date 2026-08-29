package com.byteq.ai.ragstudio.ingestion.prompt;

import com.byteq.ai.ragstudio.ingestion.domain.enums.ChunkEnrichType;
import com.byteq.ai.ragstudio.rag.prompt.config.PromptConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 分块富化提示词管理器
 * <p>
 * 维护各富化类型（关键词提取、摘要生成、元数据提取）的默认系统提示词，
 * 供 EnricherNode 在对文本块进行 AI 富化时调用大语言模型使用。
 * </p>
 * <p>
 * 提示词内容纳入统一管理（DB 优先、classpath 默认兜底），
 * 后管「提示词管理」页编辑后热重载生效。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class EnricherPromptManager {

    private static final Map<ChunkEnrichType, String> PROMPT_KEYS = new EnumMap<>(ChunkEnrichType.class);

    static {
        PROMPT_KEYS.put(ChunkEnrichType.KEYWORDS, "enricher_keywords");
        PROMPT_KEYS.put(ChunkEnrichType.SUMMARY, "enricher_summary");
        PROMPT_KEYS.put(ChunkEnrichType.METADATA, "enricher_metadata");
    }

    private final PromptConfigService promptConfigService;

    /**
     * 获取指定富化类型的默认系统提示词
     *
     * @param type 分块富化类型枚举
     * @return 对应的默认系统提示词，如果类型未注册则返回 null
     */
    public String systemPrompt(ChunkEnrichType type) {
        String key = PROMPT_KEYS.get(type);
        return key == null ? null : promptConfigService.getEffectiveContent(key);
    }
}