package com.byteq.ai.ragstudio.graph.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.aimodel.service.DefaultModelConfigService;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.framework.convention.ChatMessage;
import com.byteq.ai.ragstudio.framework.convention.ChatRequest;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.graph.config.GraphConfigService;
import com.byteq.ai.ragstudio.graph.config.GraphProperties;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphBuildLogDO;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphEntityDO;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphExtractionDO;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphRelationDO;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphBuildLogMapper;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphEntityMapper;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphExtractionMapper;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphRelationMapper;
import com.byteq.ai.ragstudio.graph.extract.GraphEntityNormalizer;
import com.byteq.ai.ragstudio.graph.extract.GraphExtractionResult;
import com.byteq.ai.ragstudio.graph.extract.GraphSchemas;
import com.byteq.ai.ragstudio.graph.extract.GraphSchemaValidator;
import com.byteq.ai.ragstudio.graph.prompt.GraphExtractionPromptManager;
import com.byteq.ai.ragstudio.graph.service.GraphExtractionService;
import com.byteq.ai.ragstudio.infra.chat.LLMService;
import com.byteq.ai.ragstudio.infra.model.ModelHealthStore;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeChunkDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 图谱抽取服务实现
 * <p>核心流程（extractForDocument）：</p>
 * <ol>
 *   <li>按 chunk 内容哈希比对抽取缓存，未变更 chunk 直接复用（零 LLM 成本）</li>
 *   <li>变更 chunk 并行调用 LLM 抽取实体与关系（JSON Schema 校验 + 失败重试一次）</li>
 *   <li>删除该文档全部旧关系 → 实体 upsert（(kb_id, canonical_name) 唯一键归并）→
 *       关系批量 upsert（跨文档重复证据 weight 累加）</li>
 *   <li>孤立实体清理：无任何关系且不属于本次抽取集合的实体删除</li>
 * </ol>
 */
@Slf4j
@Service
public class GraphExtractionServiceImpl implements GraphExtractionService {

    private final GraphProperties properties;
    private final GraphConfigService graphConfigService;
    private final LLMService llmService;
    private final ModelHealthStore healthStore;
    private final DefaultModelConfigService defaultModelConfigService;
    private final GraphEntityMapper entityMapper;
    private final GraphRelationMapper relationMapper;
    private final GraphExtractionMapper extractionMapper;
    private final GraphBuildLogMapper buildLogMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final GraphExtractionPromptManager graphExtractionPromptManager;

    /** 图谱抽取专用线程池：隔离 LLM 调用，避免打满业务线程池 */
    private final ExecutorService graphExecutor;

    /** 抽取结果状态 */
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    /** LLM 连续失败达到该次数即中止剩余 chunk（模型大概率不可用，避免批量撞墙空转） */
    private static final int ABORT_FAILURE_THRESHOLD = 3;

    public GraphExtractionServiceImpl(GraphProperties properties,
                                      GraphConfigService graphConfigService,
                                      LLMService llmService,
                                      ModelHealthStore healthStore,
                                      DefaultModelConfigService defaultModelConfigService,
                                      GraphEntityMapper entityMapper,
                                      GraphRelationMapper relationMapper,
                                      GraphExtractionMapper extractionMapper,
                                      GraphBuildLogMapper buildLogMapper,
                                      KnowledgeChunkMapper knowledgeChunkMapper,
                                      KnowledgeDocumentMapper knowledgeDocumentMapper,
                                      JdbcTemplate jdbcTemplate,
                                      ObjectMapper objectMapper,
                                      GraphExtractionPromptManager graphExtractionPromptManager) {
        this.properties = properties;
        this.graphConfigService = graphConfigService;
        this.llmService = llmService;
        this.healthStore = healthStore;
        this.defaultModelConfigService = defaultModelConfigService;
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.extractionMapper = extractionMapper;
        this.buildLogMapper = buildLogMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.graphExtractionPromptManager = graphExtractionPromptManager;
        int core = Math.max(1, Math.min(properties.getExtract().getParallelLimit(), 8));
        this.graphExecutor = Executors.newFixedThreadPool(core, new NamedThreadFactory("graph-extract"));
    }

    @Override
    public boolean isEnabled() {
        return graphConfigService.isEnabled();
    }

    @Override
    public boolean isKbGraphBuilt(String kbId) {
        if (!graphConfigService.isEnabled() || StrUtil.isBlank(kbId)) {
            return false;
        }
        // 图相关表未创建（V3 SQL 未执行）时静默视为未构建，保证检索链路不中断
        try {
            Long count = relationMapper.selectCount(Wrappers.<GraphRelationDO>lambdaQuery()
                    .eq(GraphRelationDO::getKbId, kbId));
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("查询图谱构建状态失败（可能未执行 V3 SQL），视为未构建: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public GraphExtractionReport extractForDocument(String kbId, String docId, List<VectorChunk> chunks,
                                                    String triggerType) {
        if (!graphConfigService.isEnabled()) {
            return GraphExtractionReport.empty();
        }
        if (StrUtil.isBlank(kbId) || StrUtil.isBlank(docId) || chunks == null || chunks.isEmpty()) {
            return GraphExtractionReport.empty();
        }
        long startTime = System.currentTimeMillis();
        GraphProperties.Extract config = properties.getExtract();

        // 1. 过滤图像/空内容 chunk，截断超长内容与超大文档
        List<ChunkUnit> units = new ArrayList<>();
        for (VectorChunk chunk : chunks) {
            if (chunk == null || chunk.isImage()) {
                continue;
            }
            String content = chunk.getContent();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            String chunkId = chunk.getChunkId();
            if (!StringUtils.hasText(chunkId)) {
                chunkId = IdUtil.getSnowflakeNextIdStr();
            }
            if (content.length() > config.getMaxChunkChars()) {
                content = content.substring(0, config.getMaxChunkChars());
            }
            units.add(new ChunkUnit(chunkId, content, SecureUtil.sha256(content)));
            if (units.size() >= config.getMaxChunksPerBuild()) {
                log.warn("图谱抽取超过单次构建上限({}), 截断: kbId={}, docId={}",
                        config.getMaxChunksPerBuild(), kbId, docId);
                break;
            }
        }
        if (units.isEmpty()) {
            return GraphExtractionReport.empty();
        }

        // 2. 加载既有抽取缓存，区分复用/需重抽
        List<GraphExtractionDO> existing = extractionMapper.selectList(
                Wrappers.<GraphExtractionDO>lambdaQuery().eq(GraphExtractionDO::getDocId, docId));
        Map<String, GraphExtractionDO> existingByChunk = existing.stream()
                .collect(Collectors.toMap(GraphExtractionDO::getChunkId, e -> e, (a, b) -> a));

        List<ChunkUnit> changedUnits = new ArrayList<>();
        for (ChunkUnit unit : units) {
            GraphExtractionDO cached = existingByChunk.get(unit.chunkId());
            boolean reusable = cached != null
                    && STATUS_DONE.equals(cached.getStatus())
                    && unit.contentHash().equals(cached.getChunkContentHash());
            if (!reusable) {
                changedUnits.add(unit);
            }
        }

        // 3. 并行 LLM 抽取变更 chunk
        Map<String, ExtractionOutcome> outcomes = changedUnits.isEmpty()
                ? Map.of() : extractChangedChunks(kbId, changedUnits, config);
        int llmCalls = outcomes.size();
        int failedChunks = (int) outcomes.values().stream().filter(o -> !o.success()).count();

        // 4. 持久化：删除文档旧关系 → 实体/关系 upsert → 缓存更新 → 孤立实体清理
        int relationRemoved = deleteRelationsByDoc(kbId, docId);

        Counters counters = new Counters();
        Map<String, String> canonicalToId = new HashMap<>();
        Set<String> currentCanonicals = new HashSet<>();

        for (ChunkUnit unit : units) {
            GraphExtractionDO cached = existingByChunk.get(unit.chunkId());
            if (cached != null && STATUS_DONE.equals(cached.getStatus())
                    && unit.contentHash().equals(cached.getChunkContentHash())) {
                // 复用缓存：实体/关系按缓存重建（幂等，无 LLM 调用）
                GraphExtractionResult result = parseCachedResult(cached);
                collectResult(kbId, docId, unit, result, canonicalToId, currentCanonicals, counters, true);
            } else {
                ExtractionOutcome outcome = outcomes.get(unit.chunkId());
                if (outcome == null) {
                    // 非变更但缓存状态异常（如 SKIPPED）：记录 SKIPPED
                    upsertExtraction(kbId, docId, unit, null, STATUS_SKIPPED, null, null, null);
                } else if (outcome.success()) {
                    collectResult(kbId, docId, unit, outcome.result(), canonicalToId, currentCanonicals,
                            counters, false);
                    upsertExtraction(kbId, docId, unit, outcome.result(), STATUS_DONE,
                            outcome.modelId(), outcome.durationMs(), null);
                } else {
                    upsertExtraction(kbId, docId, unit, null, STATUS_FAILED,
                            outcome.modelId(), outcome.durationMs(), outcome.error());
                }
            }
        }
        // 被移除 chunk 的抽取缓存保留（廉价且 chunkId 不复用，文档重建/重新启用时可直接复用）

        // 6. 孤立实体清理（无关系且不属于本次抽取集合）
        int orphanRemoved = cleanupOrphanEntities(kbId, currentCanonicals);

        GraphExtractionReport report = new GraphExtractionReport(
                counters.entityAdded, counters.entityMerged, counters.relationAdded, relationRemoved,
                llmCalls, units.size() - llmCalls, failedChunks, System.currentTimeMillis() - startTime);
        log.info("图谱抽取完成: kbId={}, docId={}, chunks={}, llm={}, failed={}, relations={}+{}removed, "
                        + "entities={}+{}merged, orphan={}, {}ms",
                kbId, docId, units.size(), llmCalls, failedChunks,
                counters.relationAdded, relationRemoved, counters.entityAdded, counters.entityMerged,
                orphanRemoved, report.durationMs());
        writeBuildLog(kbId, docId, triggerType, report, null);
        return report;
    }

    @Override
    public void extractForDocumentAsync(String kbId, String docId, List<VectorChunk> chunks) {
        if (!graphConfigService.isEnabled() || chunks == null || chunks.isEmpty()) {
            return;
        }
        graphExecutor.submit(() -> {
            try {
                extractForDocument(kbId, docId, chunks, "DOC");
            } catch (Exception e) {
                log.error("异步图谱抽取失败: kbId={}, docId={}", kbId, docId, e);
            }
        });
    }

    @Override
    public void extractForChunk(String kbId, String docId, String chunkId, String content) {
        if (!graphConfigService.isEnabled()) {
            return;
        }
        // 加载文档全部启用 chunk，仅替换变更 chunk 内容，其余复用缓存（零额外 LLM 成本）
        List<KnowledgeChunkDO> allChunks = knowledgeChunkMapper.selectList(
                Wrappers.<KnowledgeChunkDO>lambdaQuery()
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .eq(KnowledgeChunkDO::getEnabled, 1));
        if (allChunks.isEmpty()) {
            return;
        }
        List<VectorChunk> vectorChunks = allChunks.stream().map(c -> {
            String chunkContent = c.getId().equals(chunkId) ? content : c.getContent();
            return VectorChunk.builder()
                    .chunkId(c.getId())
                    .index(c.getChunkIndex())
                    .content(chunkContent)
                    .contentType(c.getContentType() != null ? c.getContentType() : "TEXT")
                    .build();
        }).toList();
        extractForDocument(kbId, docId, vectorChunks, "CHUNK");
    }

    @Override
    public void deleteDocumentGraph(String kbId, String docId) {
        if (!graphConfigService.isEnabled()) {
            return;
        }
        int removed = deleteRelationsByDoc(kbId, docId);
        extractionMapper.delete(Wrappers.<GraphExtractionDO>lambdaQuery()
                .eq(GraphExtractionDO::getDocId, docId));
        cleanupOrphanEntities(kbId, Set.of());
        log.info("文档图谱数据已清理: kbId={}, docId={}, relations={}", kbId, docId, removed);
    }

    @Override
    public void deleteChunkGraph(String kbId, String docId, String chunkId) {
        if (!graphConfigService.isEnabled()) {
            return;
        }
        // 仅删除该 chunk 派生的关系与孤立实体；抽取缓存保留（禁用后可复用，重新启用零 LLM 成本）
        int removed = jdbcTemplate.update(
                "DELETE FROM t_graph_relation WHERE kb_id = ? AND doc_id = ? AND source_chunk_id = ?",
                kbId, docId, chunkId);
        cleanupOrphanEntities(kbId, Set.of());
        log.info("单 chunk 图谱数据已清理: kbId={}, docId={}, chunkId={}, relations={}",
                kbId, docId, chunkId, removed);
    }

    @Override
    public String rebuildKnowledgeBase(String kbId) {
        if (!graphConfigService.isEnabled()) {
            return "图谱总开关未开启（后管「知识图谱」页可开启），无法重建";
        }
        graphExecutor.submit(() -> {
            long start = System.currentTimeMillis();
            int docCount = 0;
            int failed = 0;
            try {
                List<KnowledgeDocumentDO> docs = knowledgeDocumentMapper.selectList(
                        Wrappers.<KnowledgeDocumentDO>lambdaQuery()
                                .eq(KnowledgeDocumentDO::getKbId, kbId)
                                .eq(KnowledgeDocumentDO::getEnabled, 1));
                for (KnowledgeDocumentDO doc : docs) {
                    List<KnowledgeChunkDO> chunks = knowledgeChunkMapper.selectList(
                            Wrappers.<KnowledgeChunkDO>lambdaQuery()
                                    .eq(KnowledgeChunkDO::getDocId, doc.getId())
                                    .eq(KnowledgeChunkDO::getEnabled, 1));
                    if (chunks.isEmpty()) {
                        continue;
                    }
                    List<VectorChunk> vectorChunks = chunks.stream().map(c -> VectorChunk.builder()
                            .chunkId(c.getId())
                            .index(c.getChunkIndex())
                            .content(c.getContent())
                            .contentType(c.getContentType() != null ? c.getContentType() : "TEXT")
                            .build()).toList();
                    try {
                        extractForDocument(kbId, doc.getId(), vectorChunks, "KB");
                        docCount++;
                    } catch (Exception e) {
                        failed++;
                        log.error("知识库重建文档失败: kbId={}, docId={}", kbId, doc.getId(), e);
                    }
                }
                log.info("知识库图谱重建完成: kbId={}, docs={}, failed={}, {}ms",
                        kbId, docCount, failed, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("知识库图谱重建异常: kbId={}", kbId, e);
            }
        });
        return "知识库图谱重建任务已提交（异步执行）";
    }

    // ==================== 内部实现 ====================

    /** 并行 LLM 抽取变更 chunk（整体截止：任一 chunk 超时不阻塞其他 chunk 的持久化） */
    private Map<String, ExtractionOutcome> extractChangedChunks(String kbId, List<ChunkUnit> units,
                                                                GraphProperties.Extract config) {
        Map<String, ExtractionOutcome> outcomes = new java.util.concurrent.ConcurrentHashMap<>();
        // 模型解析一次（避免每个 chunk 重复查库）+ 熔断预检：模型不可用时快速失败，不发起批量任务
        String modelId = resolveExtractionModelId();
        if (modelId != null && healthStore.isUnavailable(modelId)) {
            log.warn("图谱抽取模型熔断中，跳过本次批量抽取: kbId={}, modelId={}", kbId, modelId);
            for (ChunkUnit unit : units) {
                outcomes.put(unit.chunkId(), ExtractionOutcome.failed("抽取模型熔断: " + modelId));
            }
            return outcomes;
        }
        AtomicBoolean aborted = new AtomicBoolean(false);
        AtomicInteger consecutiveFailures = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (ChunkUnit unit : units) {
            futures.add(CompletableFuture.runAsync(() -> {
                if (aborted.get()) {
                    outcomes.put(unit.chunkId(), ExtractionOutcome.failed("已中止（抽取模型连续失败）"));
                    return;
                }
                try {
                    outcomes.put(unit.chunkId(), extractChunkWithRetry(kbId, unit, config, modelId,
                            aborted, consecutiveFailures));
                } catch (Exception e) {
                    log.warn("chunk 图谱抽取异常: chunkId={}, error={}", unit.chunkId(), e.getMessage());
                    outcomes.put(unit.chunkId(), ExtractionOutcome.failed(e.getMessage()));
                }
            }, graphExecutor));
        }
        // 整体截止（并行等待，非逐个串行）：超时后已完成的 chunk 照常落库，
        // 未完成的记 SKIPPED，下次构建自动重试（缓存幂等）
        // 截止放宽到 4 分钟：抽取含一次重试（两次 LLM 调用），冷启动/排队模型耗时可达分钟级
        long overallDeadline = Math.max(config.getTimeoutMs() * 2L, 240_000L);
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(overallDeadline, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("批量图谱抽取部分超时（超过 {}ms，已完成的照常落库）", overallDeadline);
        }
        return outcomes;
    }

    /** 单 chunk 抽取，解析失败带修复提示重试一次 */
    private ExtractionOutcome extractChunkWithRetry(String kbId, ChunkUnit unit, GraphProperties.Extract config,
                                                    String modelId, AtomicBoolean aborted,
                                                    AtomicInteger consecutiveFailures) {
        long start = System.currentTimeMillis();
        String systemPrompt = graphExtractionPromptManager.extractionSystemPrompt(config);
        String content = unit.content();

        String raw;
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(systemPrompt),
                            ChatMessage.user(content)
                    ))
                    .temperature(config.getTemperature())
                    .jsonSchema(GraphSchemas.EXTRACTION)
                    .build();
            raw = llmService.chat(request, modelId);
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("图谱抽取 LLM 调用失败: chunkId={}, modelId={}, err={}, 连续失败={}",
                    unit.chunkId(), modelId, e.getMessage(), failures);
            if (failures >= ABORT_FAILURE_THRESHOLD) {
                aborted.set(true);
                log.warn("图谱抽取模型连续失败 {} 次，中止剩余 chunk（避免批量撞墙空转）", failures);
            }
            return ExtractionOutcome.failed("LLM 调用失败: " + e.getMessage());
        }
        consecutiveFailures.set(0);
        GraphExtractionResult result = GraphSchemaValidator.parse(raw, config.getMaxEntitiesPerChunk(),
                config.getMaxRelationsPerChunk());
        if (result == null) {
            // 重试一次：附上修复提示
            try {
                ChatRequest retry = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(systemPrompt),
                                ChatMessage.user("上一次输出无法解析。" + GraphSchemaValidator.repairHint(raw)
                                        + "\n\n文本片段：\n" + content)
                        ))
                        .temperature(config.getTemperature())
                        .jsonSchema(GraphSchemas.EXTRACTION)
                        .build();
                raw = llmService.chat(retry, modelId);
                result = GraphSchemaValidator.parse(raw, config.getMaxEntitiesPerChunk(),
                        config.getMaxRelationsPerChunk());
            } catch (Exception e) {
                log.warn("图谱抽取重试失败: chunkId={}, error={}", unit.chunkId(), e.getMessage());
            }
        }
        int duration = (int) (System.currentTimeMillis() - start);
        if (result == null) {
            return ExtractionOutcome.failed("JSON 解析失败，已重试一次");
        }
        return ExtractionOutcome.success(result, modelId, duration,
                result.entities().size(), result.relations().size());
    }

    /** 解析缓存中的抽取结果 */
    private GraphExtractionResult parseCachedResult(GraphExtractionDO cached) {
        try {
            List<GraphExtractionResult.ExtractedEntity> entities = List.of();
            List<GraphExtractionResult.ExtractedRelation> relations = List.of();
            if (StringUtils.hasText(cached.getEntityJson())) {
                entities = objectMapper.readValue(cached.getEntityJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                GraphExtractionResult.ExtractedEntity.class));
            }
            if (StringUtils.hasText(cached.getRelationJson())) {
                relations = objectMapper.readValue(cached.getRelationJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                GraphExtractionResult.ExtractedRelation.class));
            }
            return new GraphExtractionResult(entities, relations);
        } catch (Exception e) {
            log.warn("解析图谱抽取缓存失败: chunkId={}", cached.getChunkId());
            return new GraphExtractionResult(List.of(), List.of());
        }
    }

    /**
     * 收集一个 chunk 的抽取结果：实体 upsert + 关系写入 + 记录当前 canonical 集合。
     * 实体按 (kb_id, canonical_name) 唯一键归并（新实体插入、已存在补别名）。
     * 关系按 (kb_id, src, tgt, predicate) 唯一键 upsert，跨文档重复证据 weight 累加。
     */
    private void collectResult(String kbId, String docId, ChunkUnit unit, GraphExtractionResult result,
                               Map<String, String> canonicalToId, Set<String> currentCanonicals,
                               Counters counters, boolean fromCache) {
        if (result == null || (result.entities().isEmpty() && result.relations().isEmpty())) {
            return;
        }
        // 实体 upsert
        for (GraphExtractionResult.ExtractedEntity entity : result.entities()) {
            String canonical = GraphEntityNormalizer.normalizeName(entity.name());
            if (canonical.isEmpty()) {
                continue;
            }
            currentCanonicals.add(canonical);
            if (canonicalToId.containsKey(canonical)) {
                continue;
            }
            String entityId = upsertEntity(kbId, entity, canonical, counters);
            canonicalToId.put(canonical, entityId);
        }
        // 关系写入
        for (GraphExtractionResult.ExtractedRelation relation : result.relations()) {
            String srcCanonical = GraphEntityNormalizer.normalizeName(relation.source());
            String tgtCanonical = GraphEntityNormalizer.normalizeName(relation.target());
            if (srcCanonical.isEmpty() || tgtCanonical.isEmpty() || srcCanonical.equals(tgtCanonical)) {
                continue;
            }
            String srcId = canonicalToId.get(srcCanonical);
            String tgtId = canonicalToId.get(tgtCanonical);
            if (srcId == null || tgtId == null) {
                log.debug("关系端点实体缺失，跳过: {} → {} ({})",
                        relation.source(), relation.target(), relation.predicate());
                continue;
            }
            insertRelation(kbId, docId, unit.chunkId(), srcId, tgtId, relation);
            counters.relationAdded++;
        }
    }

    /** 实体 upsert：已存在则合并别名/描述，否则插入 */
    private String upsertEntity(String kbId, GraphExtractionResult.ExtractedEntity entity, String canonical,
                                Counters counters) {
        GraphEntityDO existing = entityMapper.selectOne(Wrappers.<GraphEntityDO>lambdaQuery()
                .eq(GraphEntityDO::getKbId, kbId)
                .eq(GraphEntityDO::getCanonicalName, canonical)
                .last("LIMIT 1"));
        if (existing != null) {
            // 合并别名（仅新增别名并入，不覆盖已有）
            Set<String> oldAliases = parseAliases(existing.getAliases());
            Set<String> mergedAliases = new HashSet<>(oldAliases);
            mergedAliases.addAll(GraphEntityNormalizer.buildAliases(entity.name(), canonical));
            if (mergedAliases.size() != oldAliases.size()) {
                GraphEntityDO update = new GraphEntityDO();
                update.setId(existing.getId());
                update.setAliases(toJson(mergedAliases));
                if (!StringUtils.hasText(existing.getDescription()) && StringUtils.hasText(entity.description())) {
                    update.setDescription(entity.description());
                }
                entityMapper.updateById(update);
            }
            counters.entityMerged++;
            return existing.getId();
        }
        GraphEntityDO insert = GraphEntityDO.builder()
                .kbId(kbId)
                .canonicalName(canonical)
                .displayName(entity.name() == null ? canonical : entity.name().trim())
                .entityType(GraphEntityNormalizer.normalizeType(entity.type()))
                .description(entity.description() == null ? "" : entity.description().trim())
                .aliases(toJson(GraphEntityNormalizer.buildAliases(entity.name(), canonical)))
                .createdBy(UserContext.getUsername())
                .build();
        entityMapper.insert(insert);
        counters.entityAdded++;
        return insert.getId();
    }

    /** 关系 upsert：重复 (src,tgt,predicate) 时 weight 累加、证据与 chunk 指向更新为最新 */
    private void insertRelation(String kbId, String docId, String chunkId, String srcId, String tgtId,
                                GraphExtractionResult.ExtractedRelation relation) {
        String evidence = relation.evidence() == null ? "" : relation.evidence();
        if (evidence.length() > properties.getExtract().getMaxEvidenceChars()) {
            evidence = evidence.substring(0, properties.getExtract().getMaxEvidenceChars());
        }
        jdbcTemplate.update("""
                        INSERT INTO t_graph_relation
                            (id, kb_id, source_entity_id, target_entity_id, predicate, direction, weight,
                             evidence, source_chunk_id, doc_id, create_time, update_time)
                        VALUES (?, ?, ?, ?, ?, 1, 1.0, ?, ?, ?, now(), now())
                        ON CONFLICT (kb_id, source_entity_id, target_entity_id, predicate)
                        DO UPDATE SET weight = t_graph_relation.weight + EXCLUDED.weight,
                                      evidence = EXCLUDED.evidence,
                                      source_chunk_id = EXCLUDED.source_chunk_id,
                                      doc_id = EXCLUDED.doc_id,
                                      update_time = now()
                        """,
                IdUtil.getSnowflakeNextIdStr(), kbId, srcId, tgtId, relation.predicate().trim(),
                evidence, chunkId, docId);
    }

    /** 删除文档全部关系（图谱随文档重建，幂等） */
    private int deleteRelationsByDoc(String kbId, String docId) {
        return jdbcTemplate.update(
                "DELETE FROM t_graph_relation WHERE kb_id = ? AND doc_id = ?", kbId, docId);
    }

    /**
     * 孤立实体清理：删除「无任何关系」且「不属于本次抽取集合」的实体。
     * 属于本次抽取集合但关系缺失的实体（LLM 关系引用错误）不删除，避免误删。
     * 单 SQL 找出全部无关系实体（NOT EXISTS），再按 keepCanonicals 过滤。
     */
    private int cleanupOrphanEntities(String kbId, Set<String> keepCanonicals) {
        List<String> orphanIds = new ArrayList<>();
        try {
            jdbcTemplate.query("""
                            SELECT id, canonical_name FROM t_graph_entity e
                            WHERE e.kb_id = ? AND NOT EXISTS (
                                SELECT 1 FROM t_graph_relation r
                                WHERE r.source_entity_id = e.id OR r.target_entity_id = e.id
                            )
                            """,
                    (rs, rowNum) -> {
                        if (!keepCanonicals.contains(rs.getString("canonical_name"))) {
                            orphanIds.add(rs.getString("id"));
                        }
                        return null;
                    },
                    kbId);
        } catch (Exception e) {
            log.warn("孤立实体查询失败: {}", e.getMessage());
            return 0;
        }
        if (!orphanIds.isEmpty()) {
            for (int i = 0; i < orphanIds.size(); i += 500) {
                entityMapper.deleteBatchIds(orphanIds.subList(i, Math.min(i + 500, orphanIds.size())));
            }
        }
        return orphanIds.size();
    }

    /** upsert 抽取缓存（按 chunk_id 唯一键） */
    private void upsertExtraction(String kbId, String docId, ChunkUnit unit, GraphExtractionResult result,
                                  String status, String modelId, Integer durationMs, String errorMessage) {
        GraphExtractionDO existing = extractionMapper.selectOne(Wrappers.<GraphExtractionDO>lambdaQuery()
                .eq(GraphExtractionDO::getChunkId, unit.chunkId())
                .last("LIMIT 1"));
        String entityJson = null;
        String relationJson = null;
        if (result != null) {
            entityJson = result.entities().isEmpty() ? null : toJson(result.entities());
            relationJson = result.relations().isEmpty() ? null : toJson(result.relations());
        }
        if (!StringUtils.hasText(errorMessage) && STATUS_FAILED.equals(status)) {
            errorMessage = "抽取失败";
        }
        if (existing != null) {
            GraphExtractionDO update = new GraphExtractionDO();
            update.setId(existing.getId());
            update.setKbId(kbId);
            update.setDocId(docId);
            update.setChunkContentHash(unit.contentHash());
            update.setEntityJson(entityJson);
            update.setRelationJson(relationJson);
            update.setStatus(status);
            update.setModelId(modelId);
            update.setDurationMs(durationMs);
            update.setErrorMessage(errorMessage);
            extractionMapper.updateById(update);
        } else {
            extractionMapper.insert(GraphExtractionDO.builder()
                    .kbId(kbId)
                    .docId(docId)
                    .chunkId(unit.chunkId())
                    .chunkContentHash(unit.contentHash())
                    .entityJson(entityJson)
                    .relationJson(relationJson)
                    .status(status)
                    .modelId(modelId)
                    .durationMs(durationMs)
                    .errorMessage(errorMessage)
                    .build());
        }
    }

    /** 写入构建日志 */
    private void writeBuildLog(String kbId, String docId, String triggerType, GraphExtractionReport report,
                               String errorMessage) {
        try {
            buildLogMapper.insert(GraphBuildLogDO.builder()
                    .kbId(kbId)
                    .docId(docId)
                    .triggerType(triggerType)
                    .status(report.failedChunks() > 0 ? "FAILED" : "SUCCESS")
                    .entityAdded(report.entityAdded())
                    .entityMerged(report.entityMerged())
                    .relationAdded(report.relationAdded())
                    .relationRemoved(report.relationRemoved())
                    .llmCalls(report.llmCalls())
                    .durationMs(report.durationMs())
                    .errorMessage(errorMessage)
                    .createTime(new Date())
                    .build());
        } catch (Exception e) {
            log.warn("写入图谱构建日志失败: {}", e.getMessage());
        }
    }

    /** 解析抽取模型 ID：优先 graph_extract 场景配置，缺省回退 chat 默认模型（null） */
    private String resolveExtractionModelId() {
        try {
            return defaultModelConfigService.getModelId(properties.getExtract().getModelKey());
        } catch (Exception e) {
            log.debug("解析图谱抽取模型失败，回退默认模型: {}", e.getMessage());
            return null;
        }
    }

    private Set<String> parseAliases(String aliasesJson) {
        if (!StringUtils.hasText(aliasesJson)) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(objectMapper.readValue(aliasesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("序列化 JSON 失败: {}", e.getMessage());
            return "[]";
        }
    }

    /** 统计计数器 */
    private static class Counters {
        int entityAdded;
        int entityMerged;
        int relationAdded;
    }

    /** 命名线程工厂 */
    private static class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private int counter;

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + (++counter));
            t.setDaemon(true);
            return t;
        }
    }

    /** 待抽取 chunk 单元 */
    private record ChunkUnit(String chunkId, String content, String contentHash) {
    }

    /** 单 chunk 抽取结果（成功或失败） */
    private record ExtractionOutcome(boolean success, GraphExtractionResult result, String modelId,
                                     Integer durationMs, int entityCount, int relationCount, String error) {

        static ExtractionOutcome success(GraphExtractionResult result, String modelId, int durationMs,
                                         int entityCount, int relationCount) {
            return new ExtractionOutcome(true, result, modelId, durationMs, entityCount, relationCount, null);
        }

        static ExtractionOutcome failed(String error) {
            return new ExtractionOutcome(false, null, null, null, 0, 0, error);
        }
    }
}