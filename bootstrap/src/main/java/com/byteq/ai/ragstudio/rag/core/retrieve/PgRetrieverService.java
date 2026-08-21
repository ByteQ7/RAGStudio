package com.byteq.ai.ragstudio.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class PgRetrieverService implements RetrieverService {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 事务模板：用于在同一事务中执行 SET LOCAL + 向量查询，保证 hnsw.ef_search 生效且不污染连接池 */
    private final TransactionTemplate transactionTemplate;

    public PgRetrieverService(JdbcTemplate jdbcTemplate,
                              EmbeddingService embeddingService,
                              PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.transactionManager = transactionManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** pg_trgm 扩展可用性缓存（null=未探测），决定关键词检索使用 similarity 还是位置降级打分 */
    private volatile Boolean trgmAvailable;

    /** collection → 维度缓存：检索路径每次按 collection 查库解析维度，热点 collection 高频重复 */
    private final Map<String, CacheEntry<Integer>> dimensionCache = new ConcurrentHashMap<>();

    /** collection → embedding 模型缓存：同上，避免每次检索重复查库 */
    private final Map<String, CacheEntry<String>> modelCache = new ConcurrentHashMap<>();

    /** collection → 实际数据所在维度缓存：配置维度表为空时的探测兜底结果（60s TTL） */
    private final Map<String, CacheEntry<Integer>> actualDimCache = new ConcurrentHashMap<>();

    /**
     * collection 元数据缓存 TTL：维度/模型变更需重建向量表（t_knowledge_vector_{dim}）。
     * 缓存过长会拉大"重建向量表 → 检索切到新表"的生效时间窗（期间仍查旧表，
     * 旧表删除后检索降级为空结果）。60s 窗口在热点检索路径上已足够抵消重复查库开销。
     */
    private static final long COLLECTION_META_CACHE_TTL_MS = 60_000L;

    /** 带时间戳的缓存条目 */
    private record CacheEntry<T>(T value, long timestamp) {
        boolean expired() {
            return System.currentTimeMillis() - timestamp > COLLECTION_META_CACHE_TTL_MS;
        }
    }

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

    /**
     * 批量嵌入同一 collection 的多个查询：一次远程调用完成全部向量化。
     * 向量逐位等价于逐查询 embed（模型/维度一致），失败时返回空 Map 由上层回退。
     */
    @Override
    public Map<String, float[]> embedQueriesBatch(List<String> queries, String collectionName) {
        if (queries == null || queries.isEmpty() || StrUtil.isBlank(collectionName)) {
            return Map.of();
        }
        // 去重：同一查询文本只嵌入一次，向量按原文映射
        List<String> distinct = queries.stream().distinct().toList();
        try {
            String embeddingModel = resolveEmbeddingModelFromCollection(collectionName);
            Integer kbDimension = resolveDimensionFromCollection(collectionName);
            List<List<Float>> embeddings;
            if (embeddingModel != null) {
                embeddings = embeddingService.embedBatch(distinct, embeddingModel, kbDimension);
            } else {
                embeddings = embeddingService.embedBatch(distinct);
            }
            if (embeddings == null || embeddings.size() != distinct.size()) {
                return Map.of();
            }
            Map<String, float[]> result = new java.util.LinkedHashMap<>();
            for (int i = 0; i < distinct.size(); i++) {
                List<Float> embedding = embeddings.get(i);
                if (embedding == null || embedding.isEmpty()) {
                    continue;
                }
                result.put(distinct.get(i), normalize(toArray(embedding)));
            }
            return result;
        } catch (Exception e) {
            log.warn("批量嵌入查询失败，回退逐查询嵌入: collection={}, error={}", collectionName, e.getMessage());
            return Map.of();
        }
    }

    /**
     * 跨 collection 批量嵌入：按 (embedding 模型, 维度) 分组，同一模型的所有 collection
     * 共享一次远程批量调用（如 8 个知识库 × 同一查询 = 1 次调用而非 8 次）。
     * 任一 collection 解析或嵌入失败时整体返回空 Map，由上层回退为逐 collection 嵌入。
     */
    @Override
    public Map<String, Map<String, float[]>> embedQueriesBatchPerCollection(List<String> queries, List<String> collectionNames) {
        if (queries == null || queries.isEmpty() || collectionNames == null || collectionNames.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = queries.stream().distinct().toList();
        try {
            // 1. 解析每个 collection 的 (模型, 维度)
            Map<String, EmbeddingSpec> specByCollection = new java.util.LinkedHashMap<>();
            for (String collection : collectionNames) {
                specByCollection.put(collection, new EmbeddingSpec(
                        resolveEmbeddingModelFromCollection(collection),
                        resolveDimensionFromCollection(collection)));
            }
            // 2. 按 (模型, 维度) 分组
            Map<EmbeddingSpec, List<String>> groups = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, EmbeddingSpec> e : specByCollection.entrySet()) {
                groups.computeIfAbsent(e.getValue(), k -> new java.util.ArrayList<>()).add(e.getKey());
            }
            // 3. 每组一次批量调用
            Map<String, Map<String, float[]>> result = new java.util.LinkedHashMap<>();
            for (Map.Entry<EmbeddingSpec, List<String>> group : groups.entrySet()) {
                EmbeddingSpec spec = group.getKey();
                List<List<Float>> embeddings;
                if (spec.model != null) {
                    embeddings = embeddingService.embedBatch(distinct, spec.model, spec.dimension);
                } else {
                    embeddings = embeddingService.embedBatch(distinct);
                }
                if (embeddings == null || embeddings.size() != distinct.size()) {
                    return Map.of();
                }
                Map<String, float[]> vectors = new java.util.LinkedHashMap<>();
                for (int i = 0; i < distinct.size(); i++) {
                    List<Float> embedding = embeddings.get(i);
                    if (embedding == null || embedding.isEmpty()) {
                        continue;
                    }
                    vectors.put(distinct.get(i), normalize(toArray(embedding)));
                }
                for (String collection : group.getValue()) {
                    result.put(collection, vectors);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("跨 collection 批量嵌入失败，回退逐 collection 嵌入: {}", e.getMessage());
            return Map.of();
        }
    }

    /** embedding 模型与维度组合（record 自动实现 equals/hashCode，用于分组） */
    private record EmbeddingSpec(String model, Integer dimension) {
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
        List<RetrievedChunk> result = queryVectorTable(tableName, vector, request);
        if (!result.isEmpty()) {
            return result;
        }

        // 探测兜底：配置维度表对该 collection 无任何数据时（历史维度漂移等，
        // 向量实际落在其他维度表），探测并切换到实际数据所在维度表重查，
        // 避免「配置维度表为空 → 检索永远 0 结果」。
        Integer actualDim = probeDimensionWithData(request.getCollectionName(), dimension);
        if (actualDim == null) {
            return result;
        }
        float[] reEmbedded = reembedQuery(request, actualDim);
        if (reEmbedded == null) {
            return result;
        }
        List<RetrievedChunk> fallback = queryVectorTable("t_knowledge_vector_" + actualDim, reEmbedded, request);
        if (fallback.isEmpty()) {
            log.warn("维度探测兜底检索无命中: collection={}, dim={}", request.getCollectionName(), actualDim);
        }
        return fallback;
    }

    /**
     * 执行单表向量检索（事务内 SET LOCAL hnsw.ef_search + 余弦距离排序）
     */
    private List<RetrievedChunk> queryVectorTable(String tableName, float[] vector, RetrieveRequest request) {
        try {
            // SET LOCAL 必须在事务块内才生效；与查询放在同一事务中执行，
            // 事务结束后 hnsw.ef_search 自动还原，不污染连接池中的其他连接使用方
            return transactionTemplate.execute(status -> {
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
            });
        } catch (Exception e) {
            log.warn("向量检索SQL失败: collection={}, table={}, error={}",
                    request.getCollectionName(), tableName, e.getMessage());
            return List.of();
        }
    }

    /**
     * 探测 collection 数据实际所在的维度表。
     * 仅当配置维度表对该 collection 完全无数据时才探测（避免无命中场景重复探测开销），
     * 命中后结果缓存 60s，防止反复探测。
     */
    private Integer probeDimensionWithData(String collectionName, Integer configuredDim) {
        if (collectionName == null || collectionName.isBlank()) {
            return null;
        }
        CacheEntry<Integer> cached = actualDimCache.get(collectionName);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        Integer found = null;
        for (Integer dim : new int[]{1536, 1024, 4096, 3072, 2048, 768, 512, 256, 128, 64}) {
            if (dim == configuredDim) {
                continue;
            }
            if (countRowsInTable("t_knowledge_vector_" + dim, collectionName) > 0) {
                found = dim;
                break;
            }
        }
        if (found != null) {
            log.warn("collection={} 配置维度 {} 表为空，实际数据在维度 {} 表，检索降级切换", collectionName, configuredDim, found);
            actualDimCache.put(collectionName, new CacheEntry<>(found, System.currentTimeMillis()));
        }
        return found;
    }

    private int countRowsInTable(String tableName, String collectionName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + tableName + " WHERE metadata->>'collection_name' = ?",
                    Integer.class, collectionName);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 以指定维度重新嵌入查询（探测兜底路径，需要与数据所在表维度一致）
     */
    private float[] reembedQuery(RetrieveRequest request, int dimension) {
        try {
            String model = resolveEmbeddingModelFromCollection(request.getCollectionName());
            List<Float> embedding;
            if (model != null) {
                embedding = embeddingService.embed(request.getQuery(), model, dimension);
            } else {
                embedding = embeddingService.embed(request.getQuery());
            }
            if (embedding == null || embedding.isEmpty()) {
                return null;
            }
            return normalize(toArray(embedding));
        } catch (Exception e) {
            log.warn("维度探测兜底重新嵌入失败: collection={}, dim={}, error={}",
                    request.getCollectionName(), dimension, e.getMessage());
            return null;
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
        // 仅当错误确认为「similarity 函数不存在」时才降级并持久标记；
        // 瞬时故障（超时/连接抖动等）返回空结果但不降级，避免关键词检索质量被永久拉低
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
            // 仅「similarity 函数不存在」（pg_trgm 扩展未安装）返回 null 触发上层降级；
            // 其他错误（超时、连接抖动等）返回空列表，不触发降级，避免瞬时故障永久拉低检索质量
            if (trgm && e.getMessage() != null
                    && e.getMessage().contains("does not exist")
                    && e.getMessage().contains("similarity")) {
                return null;
            }
            return List.of();
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
        CacheEntry<Integer> cached = dimensionCache.get(collectionName);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        try {
            Integer dimension = jdbcTemplate.queryForObject(
                    "SELECT dimension FROM t_knowledge_base WHERE collection_name = ? LIMIT 1",
                    Integer.class, collectionName);
            if (dimension != null && dimension > 0) {
                dimensionCache.put(collectionName, new CacheEntry<>(dimension, System.currentTimeMillis()));
            }
            return dimension;
        } catch (Exception e) {
            log.debug("通过 collectionName 查询 KB 维度失败: {}", collectionName);
            return null;
        }
    }

    private String resolveEmbeddingModelFromCollection(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) return null;
        CacheEntry<String> cached = modelCache.get(collectionName);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        try {
            String model = jdbcTemplate.queryForObject(
                    "SELECT embedding_model FROM t_knowledge_base WHERE collection_name = ? LIMIT 1",
                    String.class, collectionName);
            if (model != null && !model.isBlank()) {
                modelCache.put(collectionName, new CacheEntry<>(model, System.currentTimeMillis()));
            }
            return model;
        } catch (Exception e) {
            log.debug("通过 collectionName 查询 KB embedding 模型失败: {}", collectionName);
            return null;
        }
    }
}
