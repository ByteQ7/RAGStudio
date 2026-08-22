package com.byteq.ai.ragstudio.graph.extract;

import java.util.List;

/**
 * 图谱抽取结果
 * <p>LLM 从单个 chunk 中抽取出的实体与关系三元组集合。</p>
 *
 * @param entities 抽取实体列表
 * @param relations 抽取关系列表
 */
public record GraphExtractionResult(List<ExtractedEntity> entities, List<ExtractedRelation> relations) {

    /**
     * 抽取实体
     *
     * @param name 实体名称（原文）
     * @param type 实体类型（PERSON/ORG/DEPT/ROLE/PRODUCT/PROCESS/SYSTEM/DOC/OTHER）
     * @param description 一句话描述
     */
    public record ExtractedEntity(String name, String type, String description) {
    }

    /**
     * 抽取关系
     *
     * @param source 源实体名（引用实体 name）
     * @param target 目标实体名（引用实体 name）
     * @param predicate 关系谓词（动词短语）
     * @param evidence 证据原文（支持该关系的原句摘录）
     */
    public record ExtractedRelation(String source, String target, String predicate, String evidence) {
    }
}