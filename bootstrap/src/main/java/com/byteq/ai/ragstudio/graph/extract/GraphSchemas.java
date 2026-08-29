package com.byteq.ai.ragstudio.graph.extract;

import com.byteq.ai.ragstudio.framework.convention.ChatRequest;

import java.util.List;
import java.util.Map;

/**
 * 图谱相关结构化输出 Schema
 * <p>
 * 供支持 JSON Schema 的模型（如 Qwen 系列、vLLM 约束解码部署）约束输出结构；
 * 仅支持 JSON Output 的模型自动降级为 json_object，两者都不支持时保持纯提示词行为。
 * strict 模式下所有字段均 required（可空字段用 nullable 类型表达），满足
 * OpenAI 系供应商对严格 schema 的校验要求。
 * </p>
 */
public final class GraphSchemas {

    /** 图谱抽取（与 {@link GraphSchemaValidator} 的解析字段一一对应） */
    public static final ChatRequest.JsonSchemaSpec EXTRACTION = ChatRequest.JsonSchemaSpec.strict(
            "graph_extraction",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "entities", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "name", Map.of("type", "string"),
                                                    "type", Map.of("type", List.of("string", "null")),
                                                    "description", Map.of("type", List.of("string", "null"))),
                                            "required", List.of("name", "type", "description"),
                                            "additionalProperties", false)),
                            "relations", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "source", Map.of("type", "string"),
                                                    "target", Map.of("type", "string"),
                                                    "predicate", Map.of("type", "string"),
                                                    "evidence", Map.of("type", List.of("string", "null"))),
                                            "required", List.of("source", "target", "predicate", "evidence"),
                                            "additionalProperties", false))),
                    "required", List.of("entities", "relations"),
                    "additionalProperties", false));

    /** 查询实体抽取（与 GraphQueryEntityExtractor 的 {"entities":[{"name","type"}]} 对应） */
    public static final ChatRequest.JsonSchemaSpec QUERY_ENTITIES = ChatRequest.JsonSchemaSpec.strict(
            "graph_query_entities",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "entities", Map.of(
                                    "type", "array",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "name", Map.of("type", "string"),
                                                    "type", Map.of("type", List.of("string", "null"))),
                                            "required", List.of("name", "type"),
                                            "additionalProperties", false))),
                    "required", List.of("entities"),
                    "additionalProperties", false));

    private GraphSchemas() {
    }
}
