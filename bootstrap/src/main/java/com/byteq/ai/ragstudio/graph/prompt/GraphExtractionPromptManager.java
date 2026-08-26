package com.byteq.ai.ragstudio.graph.prompt;

import com.byteq.ai.ragstudio.graph.config.GraphProperties;

/**
 * 图谱抽取提示词管理器
 * <p>维护图谱抽取的系统提示词与查询实体识别提示词。
 * 参考 Microsoft GraphRAG 实体抽取设计：限定实体类型枚举、要求动词短语谓词、
 * 强制 JSON 输出、仅抽取明确陈述的事实。</p>
 */
public final class GraphExtractionPromptManager {

    private GraphExtractionPromptManager() {
    }

    /**
     * 图谱抽取系统提示词（按 chunk 抽取实体与关系）
     */
    public static String extractionSystemPrompt(GraphProperties.Extract config) {
        return """
                你是知识图谱抽取器。从给定的文本片段中抽取「命名实体」与「实体间的关系」。
                抽取规则：
                1. 仅抽取核心实体：只提取对理解文本核心主旨、关键事件或主要逻辑链有决定性作用的
                   实体（如核心人物、关键组织、重要地点、核心概念）。
                2. 过滤边缘信息：坚决忽略以下类型的实体：
                   - 泛泛而谈的通用名词（如「系统」「方法」「问题」「公司」等缺乏具体指代的词）。
                   - 仅出现一次且对上下文无重要影响的修饰性名词。
                   - 过于琐碎的时间、数字或无关紧要的附属物品。
                3. 宁缺毋滥：如果不确定某个实体是否重要，请不要抽取它。只输出高置信度、高价值的实体。
                4. 实体合并：如果同一实体在文中以不同形式出现，请统一使用其最标准、最完整的名称。
                5. 实体类型限定为以下枚举之一：PERSON / ORG / DEPT / ROLE / PRODUCT / PROCESS / SYSTEM / DOC / OTHER
                6. 关系谓词使用动词短语（如：汇报给、负责、属于、审批、包含、位于、支持），
                   禁止使用 is / 有 / 是 / 包含关系 等无信息量的谓词。
                7. 每个实体：name 使用原文名称，type 使用枚举值，description 不超过 40 字。
                8. 每条关系：source 与 target 必须引用实体列表中的 name；predicate 为动词短语；
                   evidence 为支持该关系的原句摘录，不超过 100 字。
                9. 只抽取文本中明确陈述的事实，禁止推断、猜测与常识补充。
                10. 不要输出 Markdown 代码块，直接输出纯 JSON。
                输出 JSON 格式（严格遵循，缺失字段视为非法）：
                {"entities":[{"name":"","type":"","description":""}],
                 "relations":[{"source":"","target":"","predicate":"","evidence":""}]}
                每个 chunk 实体不超过 %d 个、关系不超过 %d 条。
                """.formatted(config.getMaxEntitiesPerChunk(), config.getMaxRelationsPerChunk());
    }

    /**
     * 查询实体识别用户提示词：仅抽取实体，不抽关系（检索侧复用，成本更低）
     */
    public static String queryEntityUserPrompt(String question, int maxEntities) {
        return """
                从下面的用户问题中抽取可能存在于知识图谱中的命名实体（部门、岗位、人名、系统、流程、文档等）。
                只输出实体 name 与 type，不要输出关系。
                输出 JSON（不要 Markdown 代码块）：
                {"entities":[{"name":"","type":""}]}
                实体不超过 %d 个；问题中没有明确实体时输出 {"entities":[]}。
                用户问题：%s
                """.formatted(maxEntities, truncate(question, 200));
    }

    /**
     * 查询实体识别系统提示词
     */
    public static String queryEntitySystemPrompt() {
        return """
                你是知识图谱查询理解器。从用户问题中识别可用于图谱检索的命名实体。
                实体类型枚举：PERSON / ORG / DEPT / ROLE / PRODUCT / PROCESS / SYSTEM / DOC / OTHER。
                直接输出纯 JSON，不要任何解释。
                """;
    }

    private static String truncate(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : text.substring(0, max) + "...");
    }
}