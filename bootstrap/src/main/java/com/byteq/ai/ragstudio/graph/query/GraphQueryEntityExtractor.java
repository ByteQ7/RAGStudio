package com.byteq.ai.ragstudio.graph.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.graph.config.GraphProperties;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphEntityDO;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphEntityMapper;
import com.byteq.ai.ragstudio.graph.extract.GraphEntityNormalizer;
import com.byteq.ai.ragstudio.graph.extract.GraphSchemas;
import com.byteq.ai.ragstudio.graph.prompt.GraphExtractionPromptManager;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.util.LLMResponseCleaner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询实体识别器
 * <p>从用户查询中识别可锚定图谱的实体，两路兜底：</p>
 * <ol>
 *   <li><b>LLM 抽取</b>：复用抽取提示词（仅实体输出），在知识库内按规范化名/别名匹配实体</li>
 *   <li><b>trgm 关键词匹配</b>：LLM 不可用或零结果时，按关键词 ILIKE 匹配实体名/别名</li>
 * </ol>
 */
@Slf4j
@Component
public class GraphQueryEntityExtractor {

    private final GraphProperties properties;
    private final LLMService llmService;
    private final DefaultModelConfigService defaultModelConfigService;
    private final GraphEntityMapper entityMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GraphExtractionPromptManager graphExtractionPromptManager;

    private static final Gson GSON = new Gson();

    /** 查询文本 → LLM 抽取实体名缓存（同一查询多 KB 共享一次 LLM 调用） */
    private final java.util.concurrent.ConcurrentHashMap<String, List<QueryEntityName>> nameCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * LLM 抽取出的实体名（未落库匹配）
     */
    public record QueryEntityName(String name, String type) {
    }

    /**
     * 匹配到知识库实体的查询实体
     *
     * @param entityId 图谱实体 ID
     * @param canonicalName 规范化名称
     * @param displayName 展示名
     * @param score 匹配分（1.0 精确 / 0.8 别名 / 0.6 子串）
     */
    public record QueryEntity(String entityId, String canonicalName, String displayName, double score) {
    }

    public GraphQueryEntityExtractor(GraphProperties properties,
                                     LLMService llmService,
                                     DefaultModelConfigService defaultModelConfigService,
                                     GraphEntityMapper entityMapper,
                                     JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper,
                                     GraphExtractionPromptManager graphExtractionPromptManager) {
        this.properties = properties;
        this.llmService = llmService;
        this.defaultModelConfigService = defaultModelConfigService;
        this.entityMapper = entityMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.graphExtractionPromptManager = graphExtractionPromptManager;
    }

    /**
     * 从查询中识别图谱实体（LLM + trgm 两路兜底）
     *
     * @param question 查询文本（改写后的问题或子问题）
     * @param kbId 知识库 ID
     * @return 匹配实体列表（按分数降序，最多 query-entity-limit 个）
     */
    public List<QueryEntity> extract(String question, String kbId) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(kbId)) {
            return List.of();
        }
        int limit = Math.max(1, properties.getRetrieval().getQueryEntityLimit());

        // 路 1：LLM 抽取实体名（按查询文本缓存，多 KB 共享一次调用）
        List<QueryEntityName> names = extractNamesCached(question);
        List<QueryEntity> matched = new ArrayList<>();
        if (!names.isEmpty()) {
            for (QueryEntityName name : names) {
                QueryEntity entity = matchByName(kbId, name.name());
                if (entity != null) {
                    matched.add(entity);
                }
                if (matched.size() >= limit) {
                    break;
                }
            }
        }

        // 路 2：LLM 零结果/失败 → trgm 关键词兜底
        if (matched.isEmpty()) {
            matched = matchByKeyword(question, kbId, limit);
        }
        matched.sort((a, b) -> Double.compare(b.score(), a.score()));
        return matched.size() > limit ? matched.subList(0, limit) : matched;
    }

    /** LLM 抽取实体名（按查询文本缓存） */
    private List<QueryEntityName> extractNamesCached(String question) {
        List<QueryEntityName> cached = nameCache.get(question);
        if (cached != null) {
            return cached;
        }
        List<QueryEntityName> names = extractNamesByLlm(question);
        nameCache.put(question, names == null ? List.of() : names);
        return names == null ? List.of() : names;
    }

    /**
     * 按规范化名精确匹配，未命中时按别名包含匹配
     */
    private QueryEntity matchByName(String kbId, String rawName) {
        String canonical = GraphEntityNormalizer.normalizeName(rawName);
        if (canonical.isEmpty()) {
            return null;
        }
        // 精确：规范化名一致
        GraphEntityDO entity = entityMapper.selectOne(
                new LambdaQueryWrapper<GraphEntityDO>()
                        .eq(GraphEntityDO::getKbId, kbId)
                        .eq(GraphEntityDO::getCanonicalName, canonical)
                        .last("LIMIT 1"));
        if (entity != null) {
            return new QueryEntity(entity.getId(), entity.getCanonicalName(),
                    entity.getDisplayName(), 1.0);
        }
        // 别名包含匹配（aliases JSONB 数组 contains）
        Set<String> aliasSet = new LinkedHashSet<>();
        aliasSet.add(rawName.trim());
        aliasSet.add(canonical);
        String aliasJson = toJsonArray(aliasSet);
        try {
            List<GraphEntityDO> aliasHit = jdbcTemplate.query(
                    "SELECT * FROM t_graph_entity WHERE kb_id = ? AND aliases @> ?::jsonb LIMIT 1",
                    (rs, rowNum) -> {
                        GraphEntityDO e = new GraphEntityDO();
                        e.setId(rs.getString("id"));
                        e.setCanonicalName(rs.getString("canonical_name"));
                        e.setDisplayName(rs.getString("display_name"));
                        return e;
                    },
                    kbId, aliasJson);
            if (!aliasHit.isEmpty()) {
                GraphEntityDO e = aliasHit.get(0);
                return new QueryEntity(e.getId(), e.getCanonicalName(), e.getDisplayName(), 0.8);
            }
        } catch (Exception e) {
            log.debug("别名匹配查询失败: {}", e.getMessage());
        }
        return null;
    }

    /** trgm/子串关键词兜底匹配 */
    private List<QueryEntity> matchByKeyword(String question, String kbId, int limit) {
        List<String> keywords = extractKeywords(question);
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<QueryEntity> result = new ArrayList<>();
        // 1. 精确名匹配
        for (String kw : keywords) {
            GraphEntityDO exact = entityMapper.selectOne(
                    new LambdaQueryWrapper<GraphEntityDO>()
                            .eq(GraphEntityDO::getKbId, kbId)
                            .eq(GraphEntityDO::getCanonicalName, kw)
                            .last("LIMIT 1"));
            if (exact != null) {
                result.add(new QueryEntity(exact.getId(), exact.getCanonicalName(),
                        exact.getDisplayName(), 1.0));
            }
        }
        if (result.size() >= limit) {
            return result;
        }
        // 2. 子串匹配（ILIKE）
        StringBuilder sql = new StringBuilder(
                "SELECT id, canonical_name, display_name FROM t_graph_entity WHERE kb_id = ? AND (");
        List<Object> params = new ArrayList<>();
        params.add(kbId);
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("canonical_name ILIKE ? OR display_name ILIKE ? OR aliases::text ILIKE ?");
            params.add("%" + keywords.get(i) + "%");
            params.add("%" + keywords.get(i) + "%");
            params.add("%" + keywords.get(i) + "%");
        }
        sql.append(") ORDER BY length(canonical_name) LIMIT ?");
        params.add(limit - result.size());
        try {
            result.addAll(jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new QueryEntity(
                    rs.getString("id"),
                    rs.getString("canonical_name"),
                    rs.getString("display_name"),
                    0.6), params.toArray()));
        } catch (Exception e) {
            log.debug("图谱实体关键词匹配失败: {}", e.getMessage());
        }
        return result;
    }

    /** LLM 抽取查询实体名 */
    private List<QueryEntityName> extractNamesByLlm(String question) {
        try {
            String modelId = defaultModelConfigService.getModelId(properties.getExtract().getModelKey());
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(graphExtractionPromptManager.queryEntitySystemPrompt()),
                            ChatMessage.user(graphExtractionPromptManager.queryEntityUserPrompt(
                                    question, properties.getRetrieval().getQueryEntityLimit()))
                    ))
                    .temperature(0.1D)
                    .jsonSchema(GraphSchemas.QUERY_ENTITIES)
                    .build();
            String raw = llmService.chat(request, modelId);
            return parseNames(raw);
        } catch (Exception e) {
            log.debug("查询实体 LLM 抽取失败，走关键词兜底: {}", e.getMessage());
            return List.of();
        }
    }

    /** 解析 {"entities":[{"name":"","type":""}]} */
    private List<QueryEntityName> parseNames(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            String body = LLMResponseCleaner.extractJson(cleaned);
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) {
                return List.of();
            }
            JsonObject root = element.getAsJsonObject();
            JsonElement entitiesNode = root.get("entities");
            if (entitiesNode == null || !entitiesNode.isJsonArray()) {
                return List.of();
            }
            List<QueryEntityName> names = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement e : entitiesNode.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject obj = e.getAsJsonObject();
                JsonElement nameEl = obj.get("name");
                if (nameEl == null || nameEl.isJsonNull()) {
                    continue;
                }
                String name = nameEl.getAsString();
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                String canonical = GraphEntityNormalizer.normalizeName(name);
                if (canonical.isEmpty() || !seen.add(canonical)) {
                    continue;
                }
                String type = obj.get("type") == null || obj.get("type").isJsonNull()
                        ? "OTHER" : obj.get("type").getAsString();
                names.add(new QueryEntityName(name.trim(), type));
                if (names.size() >= properties.getRetrieval().getQueryEntityLimit()) {
                    break;
                }
            }
            return names;
        } catch (Exception e) {
            log.debug("解析查询实体失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 简单关键词拆分（复用滑窗思路：长中文串补 3 字符窗口） */
    private List<String> extractKeywords(String question) {
        String[] words = question.split("[\\s,，。.；;：:！!？?]+");
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        for (String w : words) {
            if (w.isBlank() || w.length() < 2) {
                continue;
            }
            keywords.add(w);
            if (w.length() >= 5 && !w.matches(".*[A-Za-z0-9].*")) {
                for (int i = 0; i + 3 <= w.length(); i++) {
                    keywords.add(w.substring(i, i + 3));
                }
            }
        }
        return new ArrayList<>(keywords);
    }

    private String toJsonArray(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }
}