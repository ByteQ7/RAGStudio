package com.byteq.ai.ragstudio.graph.extract;

import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图谱抽取结果 Schema 校验与解析
 * <p>将 LLM 返回的原始 JSON 解析为 {@link GraphExtractionResult}：
 * 清洗 Markdown 围栏 → 提取 JSON 主体 → 逐字段校验 → 截断到配置上限。
 * 校验失败的记录给出可诊断的错误原因，供上层决定是否重试。</p>
 */
@Slf4j
public final class GraphSchemaValidator {

    private static final Gson GSON = new Gson();

    private GraphSchemaValidator() {
    }

    /**
     * 解析 LLM 原始响应为结构化抽取结果
     *
     * @param raw LLM 原始响应
     * @param maxEntities 实体上限（超出截断）
     * @param maxRelations 关系上限（超出截断）
     * @return 解析结果；解析失败返回 null
     */
    public static GraphExtractionResult parse(String raw, int maxEntities, int maxRelations) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        JsonElement element = parseJson(raw);
        if (element == null || !element.isJsonObject()) {
            log.debug("图谱抽取响应非 JSON 对象: {}", truncate(raw, 200));
            return null;
        }
        JsonObject root = element.getAsJsonObject();

        List<GraphExtractionResult.ExtractedEntity> entities = parseEntities(root.get("entities"), maxEntities);
        List<GraphExtractionResult.ExtractedRelation> relations = parseRelations(root.get("relations"), maxRelations);
        if (entities.isEmpty() && relations.isEmpty()) {
            log.debug("图谱抽取结果为空: {}", truncate(raw, 200));
            return null;
        }
        return new GraphExtractionResult(entities, relations);
    }

    /**
     * 解析实体列表：校验 name 非空、类型合法；按 name 去重（同 chunk 内同名实体合并）
     */
    private static List<GraphExtractionResult.ExtractedEntity> parseEntities(JsonElement node, int max) {
        if (node == null || !node.isJsonArray()) {
            return List.of();
        }
        List<GraphExtractionResult.ExtractedEntity> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray arr = node.getAsJsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject obj = e.getAsJsonObject();
            String name = getString(obj, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String type = GraphEntityNormalizer.normalizeType(getString(obj, "type"));
            String description = getString(obj, "description");
            String canonical = GraphEntityNormalizer.normalizeName(name);
            if (canonical.isEmpty() || !seen.add(canonical)) {
                continue;
            }
            result.add(new GraphExtractionResult.ExtractedEntity(
                    name.trim(), type, description == null ? "" : description.trim()));
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    /**
     * 解析关系列表：校验 source/target 非空、谓词合法、剔除自环
     */
    private static List<GraphExtractionResult.ExtractedRelation> parseRelations(JsonElement node, int max) {
        if (node == null || !node.isJsonArray()) {
            return List.of();
        }
        List<GraphExtractionResult.ExtractedRelation> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JsonArray arr = node.getAsJsonArray();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject obj = e.getAsJsonObject();
            String source = getString(obj, "source");
            String target = getString(obj, "target");
            String predicate = getString(obj, "predicate");
            if (source == null || target == null || source.isBlank() || target.isBlank()) {
                continue;
            }
            if (!GraphEntityNormalizer.isValidPredicate(predicate)) {
                continue;
            }
            String srcCanonical = GraphEntityNormalizer.normalizeName(source);
            String tgtCanonical = GraphEntityNormalizer.normalizeName(target);
            if (srcCanonical.isEmpty() || tgtCanonical.isEmpty() || srcCanonical.equals(tgtCanonical)) {
                continue;
            }
            String evidence = getString(obj, "evidence");
            if (evidence != null) {
                evidence = evidence.trim();
            }
            String dedupKey = srcCanonical + "|" + predicate + "|" + tgtCanonical;
            if (!seen.add(dedupKey)) {
                continue;
            }
            result.add(new GraphExtractionResult.ExtractedRelation(
                    source.trim(), target.trim(), predicate.trim(),
                    evidence == null || evidence.length() > 200 ? "" : evidence));
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    /**
     * 修复提示：解析失败时告知 LLM 问题所在，供重试
     */
    public static String repairHint(String raw) {
        if (raw == null || raw.isBlank()) {
            return "输出为空，请严格按照 JSON 格式输出实体与关系。";
        }
        if (raw.contains("```")) {
            return "不要使用 Markdown 代码块包裹，直接输出纯 JSON。";
        }
        return "输出不是合法的 JSON 对象，请检查字段名（entities/relations）与引号闭合。";
    }

    private static JsonElement parseJson(String raw) {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        String body = LLMResponseCleaner.extractJson(cleaned);
        try {
            return JsonParser.parseString(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String getString(JsonObject obj, String field) {
        JsonElement e = obj.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}