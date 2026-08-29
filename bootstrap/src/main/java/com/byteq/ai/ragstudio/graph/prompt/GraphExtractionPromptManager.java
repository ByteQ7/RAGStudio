package com.byteq.ai.ragstudio.graph.prompt;

import com.byteq.ai.ragstudio.graph.config.GraphProperties;
import com.byteq.ai.ragstudio.rag.core.prompt.PromptTemplateUtils;
import com.byteq.ai.ragstudio.rag.prompt.config.PromptConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 图谱抽取提示词管理器
 * <p>维护图谱抽取的系统提示词与查询实体识别提示词。
 * 参考 Microsoft GraphRAG 实体抽取设计：限定实体类型枚举、要求动词短语谓词、
 * 强制 JSON 输出、仅抽取明确陈述的事实。</p>
 * <p>
 * 提示词内容纳入统一管理（DB 优先、classpath 默认兜底），
 * 后管「提示词管理」页编辑后热重载生效。
 * </p>
 */
@Component
@RequiredArgsConstructor
public class GraphExtractionPromptManager {

    private final PromptConfigService promptConfigService;

    /**
     * 图谱抽取系统提示词（按 chunk 抽取实体与关系）
     */
    public String extractionSystemPrompt(GraphProperties.Extract config) {
        String template = promptConfigService.getEffectiveContent("graph_extraction_system");
        return PromptTemplateUtils.fillSlots(template, Map.of(
                "max_entities", String.valueOf(config.getMaxEntitiesPerChunk()),
                "max_relations", String.valueOf(config.getMaxRelationsPerChunk())));
    }

    /**
     * 查询实体识别用户提示词：仅抽取实体，不抽关系（检索侧复用，成本更低）
     */
    public String queryEntityUserPrompt(String question, int maxEntities) {
        String template = promptConfigService.getEffectiveContent("graph_query_entity_user");
        return PromptTemplateUtils.fillSlots(template, Map.of(
                "max_entities", String.valueOf(maxEntities),
                "question", truncate(question, 200)));
    }

    /**
     * 查询实体识别系统提示词
     */
    public String queryEntitySystemPrompt() {
        return promptConfigService.getEffectiveContent("graph_query_entity_system");
    }

    private static String truncate(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : text.substring(0, max) + "...");
    }
}
