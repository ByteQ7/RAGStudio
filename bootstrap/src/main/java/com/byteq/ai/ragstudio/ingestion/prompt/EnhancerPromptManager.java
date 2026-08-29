package com.byteq.ai.ragstudio.ingestion.prompt;

import com.byteq.ai.ragstudio.ingestion.domain.enums.EnhanceType;
import com.byteq.ai.ragstudio.rag.prompt.config.PromptConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 文档增强提示词管理器
 * <p>
 * 维护各增强类型（上下文增强、关键词提取、问题生成、元数据提取）的默认系统提示词，
 * 供 EnhancerNode 在调用大语言模型时使用。
 * </p>
 * <p>
 * 提示词内容纳入统一管理（DB 优先、classpath 默认兜底），
 * 后管「提示词管理」页编辑后热重载生效。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class EnhancerPromptManager {

    private static final Map<EnhanceType, String> PROMPT_KEYS = new EnumMap<>(EnhanceType.class);

    static {
        PROMPT_KEYS.put(EnhanceType.CONTEXT_ENHANCE, "enhancer_context");
        PROMPT_KEYS.put(EnhanceType.KEYWORDS, "enhancer_keywords");
        PROMPT_KEYS.put(EnhanceType.QUESTIONS, "enhancer_questions");
        PROMPT_KEYS.put(EnhanceType.METADATA, "enhancer_metadata");
    }

    private final PromptConfigService promptConfigService;

    /**
     * 获取指定增强类型的默认系统提示词
     *
     * @param type 增强类型枚举
     * @return 对应的默认系统提示词，如果类型未注册则返回 null
     */
    public String systemPrompt(EnhanceType type) {
        String key = PROMPT_KEYS.get(type);
        return key == null ? null : promptConfigService.getEffectiveContent(key);
    }
}