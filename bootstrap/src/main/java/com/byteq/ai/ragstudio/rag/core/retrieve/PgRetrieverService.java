package com.byteq.ai.ragstudio.rag.core.retrieve;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgRetrieverService implements RetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** pg_trgm 扩展可用性缓存（null=未探测），决定关键词检索使用 similarity 还是位置降级打分 */
    private volatile Boolean trgmAvailable;

    /** 缺失 pg_trgm 时 similarity 函数不存在的错误特征 */
    private static final String TRGM_FUNCTION_MISSING = "does not exist";

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest request) {
        String embeddingModel = resolveEmbeddingModelFromCollection(request.getCollectionName());
        Integer kbDimension = resolveDimensionFromCollection(request.getCollectionName());
        List<Float> embedding;
        if (embeddingModel != null) {
            embedding = embeddingService.embed(request.getQuery(), embeddingModel, kbDimension);
        } else {
            embedding = embeddingService.embed(request.getQuery());
        }
        if (embedding == null || embedding.isEmpty()) {
            throw new IllegalStateException("Embedding 服务返回空向量，无法执行检索。请检查 Embedding 模型配置和服务状态。");
        }
        float[] vector = normalize(toArray(embedding));
        return retrieveByVector(vector, request);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest request) {
        Integer dimension = request.getDimension();
        if (dimension == null || dimension <= 0) {
            dimension = resolveDimensionFromCollection(request.getCollectionName());
        }
        if (dimension == null || dimension <= 0) {
            dimension = vector.length;
        }

        // 查询向量维度与表维度不一致时，SQL 会失败并污染连接。提前返回空列表。
        if (dimension != vector.length) {
            log.warn("查询向量维度({})与KB维度({})不一致，跳过collection={}的向量检索",
                    vector.length, dimension, request.getCollectionName());
            return List.of();
        }

        String tableName = "t_knowledge_vector_" + dimension;
        try {
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 200");

            String vectorLiteral = toVectorLiteral(vector);
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            return jdbcTemplate.query(
                    "SELECT id, content, metadata, " +
                            "COALESCE(NULLIF(content_type, 'TEXT'), metadata->>'content_type', 'TEXT') AS content_type, " +
                            "1 - (embedding <=> ?::vector) AS score FROM " + tableName
                            + " WHERE metadata->>'collection_name' = ? ORDER BY embedding <=> ?::vector LIMIT ?",
                    (rs, rowNum) -> buildChunk(rs),
                    vectorLiteral, request.getCollectionName(), vectorLiteral, request.getTopK()
            );
        } catch (Exception e) {
            log.warn("向量检索SQL失败: collection={}, dim={}, error={}",
                    request.getCollectionName(), dimension, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<RetrievedChunk> retrieveByKeyword(String query, RetrieveRequest request) {
        String tableName = resolveVectorTable(request);
        if (tableName == null) {
            log.warn("关键词检索无法确定向量表，collection={}", request.getCollectionName());
            return List.of();
        }

        // 关键词提取：中文无空格句子补充 3 字符滑窗，避免整句精确子串匹配导致零命中
        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) {
            keywords = List.of(query);
        }
        if (keywords.size() > 16) {
            keywords = keywords.subList(0, 16);
        }

        // pg_trgm 可用性探测（缓存），决定使用 similarity 打分还是位置降级打分
        if (trgmAvailable == null) {
            trgmAvailable = checkTrgmExtension();
            log.info("pg_trgm 扩展可用: {}", trgmAvailable);
        }

        List<RetrievedChunk> result = tryKeywordQuery(tableName, request, keywords, trgmAvailable);

        // similarity 函数不存在（扩展未安装）时降级为不依赖扩展的位置打分
        if (result == null && Boolean.TRUE.equals(trgmAvailable)) {
            trgmAvailable = false;
            log.warn("pg_trgm 扩展不可用，关键词检索降级为关键词位置打分");
            result = tryKeywordQuery(tableName, request, keywords, false);
        }

        if (result == null) {
            log.warn("关键词检索失败: collection={}, table={}", request.getCollectionName(), tableName);
            return List.of();
        }
        return result;
    }

    /**
     * 执行一次关键词检索，SQL 异常时返回 null（供上层降级重试）
     *
     * @param trgm 是否使用 pg_trgm 的 similarity 打分
     */
    private List<RetrievedChunk> tryKeywordQuery(String tableName, RetrieveRequest request,
                                                 List<String> keywords, boolean trgm) {
        String scoreExpr;
        if (trgm) {
            // 取各关键词相似度最大值（整句 similarity 在长文本下全为 0，无法区分相关度）
            scoreExpr = "GREATEST(" + keywords.stream()
                    .map(w -> "similarity(content, ?)")
                    .collect(Collectors.joining(", ")) + ")";
        } else {
            // 无 pg_trgm 降级：按关键词首次命中位置排序（越靠前越相关），LEAST 忽略未命中的 NULL
            scoreExpr = "-LEAST(" + keywords.stream()
                    .map(w -> "NULLIF(position(lower(?) in lower(content)), 0)")
                    .collect(Collectors.joining(", ")) + ")";
        }

        StringBuilder sql = new StringBuilder(
            "SELECT id, content, metadata, " +
            "COALESCE(NULLIF(content_type, 'TEXT'), metadata->>'content_type', 'TEXT') AS content_type, " +
            scoreExpr + " AS score FROM " + tableName +
            " WHERE metadata->>'collection_name' = ? AND ("
        );
        java.util.List<Object> params = new java.util.ArrayList<>();
        params.addAll(keywords);
        params.add(request.getCollectionName());

        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("content ILIKE ?");
            params.add("%" + keywords.get(i) + "%");
        }
        sql.append(") ORDER BY score DESC LIMIT ?");
        params.add(request.getTopK());

        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> buildChunk(rs),
                params.toArray(new Object[0])
            );
        } catch (Exception e) {
            log.warn("关键词检索SQL失败: table={}, error={}", tableName, e.getMessage());
            return null;
        }
    }

    /**
     * 探测 pg_trgm 扩展是否已安装
     */
    private boolean checkTrgmExtension() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'",
                    Integer.class);
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("探测 pg_trgm 扩展失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析关键词列表：
     * <ul>
     *   <li>按常见分隔符拆分，过滤过短的词</li>
     *   <li>对不含字母/数字的长中文串补充 3 字符滑窗（如"市场份额是多少" → "市场份"、"场份额"…），
     *       使连续中文内容也能被子串匹配命中</li>
     * </ul>
     */
    private List<String> extractKeywords(String query) {
        String[] words = query.split("[\\s,，。.；;：:！!？?]+");
        java.util.LinkedHashSet<String> keywords = new java.util.LinkedHashSet<>();
        for (String w : words) {
            if (w.isBlank() || w.length() < 2) {
                continue;
            }
            keywords.add(w);
            // 长中文串（无空格、无字母数字）补充 3 字符滑窗
            if (w.length() >= 5 && !w.matches(".*[A-Za-z0-9].*")) {
                for (int i = 0; i + 3 <= w.length(); i++) {
                    keywords.add(w.substring(i, i + 3));
                }
            }
        }
        return new java.util.ArrayList<>(keywords);
    }

    /**
     * 确定关键词检索使用的向量表：
     * <ul>
     *   <li>优先使用 KB 配置的维度（t_knowledge_base.dimension）</li>
     *   <li>维度解析失败时按候选维度探测：表存在且包含该 collection 数据的第一个表生效，
     *       避免盲选 1536 导致查错表而永远返回空</li>
     * </ul>
     */
    private String resolveVectorTable(RetrieveRequest request) {
        Integer dimension = request.getDimension();
        if (dimension == null || dimension <= 0) {
            dimension = resolveDimensionFromCollection(request.getCollectionName());
        }
        if (dimension != null && dimension > 0) {
            return "t_knowledge_vector_" + dimension;
        }

        log.warn("collection={} 维度解析失败，按候选维度探测向量表", request.getCollectionName());
        List<Integer> candidates = new java.util.ArrayList<>();
        for (int dim : new int[]{1536, 1024, 4096, 3072, 2048}) {
            if (candidates.contains(dim)) continue;
            candidates.add(dim);
        }
        for (Integer dim : candidates) {
            String table = "t_knowledge_vector_" + dim;
            try {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE metadata->>'collection_name' = ?",
                        Integer.class, request.getCollectionName());
                if (count != null && count > 0) {
                    log.info("关键词检索探测到向量表: {} (count={})", table, count);
                    return table;
                }
            } catch (Exception e) {
                log.debug("向量表探测失败: {}", table);
            }
        }
        return null;
    }

    private float[] normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    private float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }

    private RetrievedChunk buildChunk(java.sql.ResultSet rs) throws java.sql.SQLException {
        String metadataJson = rs.getString("metadata");
        Map<String, Object> metadata = Map.of();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.debug("解析 metadata JSON 失败: chunkId={}", rs.getString("id"));
            }
        }
        String contentType = rs.getString("content_type");
        if (contentType == null || contentType.isBlank()) contentType = "TEXT";
        return RetrievedChunk.builder()
                .id(rs.getString("id"))
                .text(rs.getString("content"))
                .score(rs.getFloat("score"))
                .metadata(metadata)
                .contentType(contentType)
                .build();
    }

    private Integer resolveDimensionFromCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT dimension FROM t_knowledge_base WHERE collection_name = ? LIMIT 1",
                    Integer.class, collectionName);
        } catch (Exception e) {
            log.debug("通过 collectionName 查询 KB 维度失败: {}", collectionName);
            return null;
        }
    }

    private String resolveEmbeddingModelFromCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT embedding_model FROM t_knowledge_base WHERE collection_name = ? LIMIT 1",
                    String.class, collectionName);
        } catch (Exception e) {
            log.debug("通过 collectionName 查询 KB embedding 模型失败: {}", collectionName);
            return null;
        }
    }
}
