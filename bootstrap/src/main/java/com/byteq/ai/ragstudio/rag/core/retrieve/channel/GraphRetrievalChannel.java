package com.byteq.ai.ragstudio.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.graph.config.GraphConfigService;
import com.byteq.ai.ragstudio.graph.config.GraphProperties;
import com.byteq.ai.ragstudio.graph.query.GraphQueryEntityExtractor;
import com.byteq.ai.ragstudio.graph.query.GraphSubgraphExpander;
import com.byteq.ai.ragstudio.graph.service.GraphExtractionService;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeChunkDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱局部检索通道（GraphRetrievalChannel）
 * <p>
 * 以查询实体为锚点做 K 跳子图展开，将子图中命中的关系三元组映射回源 chunk 返回。
 * 输出类型与向量/关键词通道一致（{@link RetrievedChunk}），因此可无缝接入
 * RRF 融合 → 去重 → Rerank → 语义裁剪 → 引用 [^chunk_N] 的既有链路。
 * </p>
 */
@Slf4j
@Component
public class GraphRetrievalChannel implements SearchChannel {

    private final GraphProperties properties;
    private final GraphConfigService graphConfigService;
    private final GraphExtractionService graphExtractionService;
    private final GraphQueryEntityExtractor queryEntityExtractor;
    private final GraphSubgraphExpander subgraphExpander;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    private static final int TOPK_MULTIPLIER = 3;
    private static final int MAX_EVIDENCE_PER_CHUNK = 10;

    public GraphRetrievalChannel(GraphProperties properties,
                                 GraphConfigService graphConfigService,
                                 GraphExtractionService graphExtractionService,
                                 GraphQueryEntityExtractor queryEntityExtractor,
                                 GraphSubgraphExpander subgraphExpander,
                                 KnowledgeBaseMapper knowledgeBaseMapper,
                                 KnowledgeChunkMapper knowledgeChunkMapper) {
        this.properties = properties;
        this.graphConfigService = graphConfigService;
        this.graphExtractionService = graphExtractionService;
        this.queryEntityExtractor = queryEntityExtractor;
        this.subgraphExpander = subgraphExpander;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
    }

    @Override
    public String getName() {
        return "GraphLocal";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!graphConfigService.isEnabled() || !graphConfigService.isRetrievalEnabled()) {
            return false;
        }
        List<String> collections = context.getSelectedCollectionNames();
        if (CollUtil.isEmpty(collections)) {
            return false;
        }
        String kbId = resolveKbId(collections.get(0));
        return kbId != null && graphExtractionService.isKbGraphBuilt(kbId);
    }

    @Override
    @RagTraceNode(name = "图谱检索", type = "GRAPH_RETRIEVE")
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        List<String> collections = context.getSelectedCollectionNames();
        if (CollUtil.isEmpty(collections)) {
            return emptyResult(context, startTime);
        }
        String kbId = resolveKbId(collections.get(0));
        if (kbId == null || !graphExtractionService.isKbGraphBuilt(kbId)) {
            return emptyResult(context, startTime);
        }

        String question = context.getMainQuestion();
        if (!StringUtils.hasText(question)) {
            return emptyResult(context, startTime);
        }

        // 1. 查询实体识别（LLM + trgm 兜底）
        List<GraphQueryEntityExtractor.QueryEntity> seeds = queryEntityExtractor.extract(question, kbId);
        if (CollUtil.isEmpty(seeds)) {
            log.debug("图谱检索未识别到实体, question={}", question);
            return emptyResult(context, startTime);
        }

        // 2. K 跳子图展开
        GraphSubgraphExpander.ExpandResult expand = subgraphExpander.expand(kbId, seeds);
        if (expand == null || expand.isEmpty()) {
            return emptyResult(context, startTime);
        }

        // 3. 三元组 → 证据 chunk 分组（按 source_chunk_id）
        Map<String, List<GraphSubgraphExpander.GraphEdge>> edgesByChunk = new LinkedHashMap<>();
        for (GraphSubgraphExpander.GraphEdge edge : expand.edges()) {
            if (!StringUtils.hasText(edge.chunkId())) {
                continue;
            }
            List<GraphSubgraphExpander.GraphEdge> list =
                    edgesByChunk.computeIfAbsent(edge.chunkId(), k -> new ArrayList<>());
            if (list.size() < MAX_EVIDENCE_PER_CHUNK) {
                list.add(edge);
            }
        }
        if (edgesByChunk.isEmpty()) {
            return emptyResult(context, startTime);
        }

        // 4. 拉取启用中的 chunk 内容（禁用的 chunk 自动排除，杜绝悬挂引用）
        List<String> chunkIds = new ArrayList<>(edgesByChunk.keySet());
        List<KnowledgeChunkDO> chunkRows = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .in(KnowledgeChunkDO::getId, chunkIds)
                        .eq(KnowledgeChunkDO::getEnabled, 1));
        if (CollUtil.isEmpty(chunkRows)) {
            return emptyResult(context, startTime);
        }

        // 5. 构建 RetrievedChunk（带图谱证据 metadata）
        int limit = Math.max(1, context.getTopK() * TOPK_MULTIPLIER);
        List<RetrievedChunk> chunks = new ArrayList<>();
        for (KnowledgeChunkDO row : chunkRows) {
            List<GraphSubgraphExpander.GraphEdge> edges = edgesByChunk.get(row.getId());
            if (CollUtil.isEmpty(edges)) {
                continue;
            }
            double maxScore = edges.stream().mapToDouble(GraphSubgraphExpander.GraphEdge::score).max().orElse(0);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("graph_hit", true);
            metadata.put("graph_evidence", buildEvidence(edges));
            chunks.add(RetrievedChunk.builder()
                    .id(row.getId())
                    .text(row.getContent())
                    .score((float) maxScore)
                    .contentType("TEXT")
                    .metadata(metadata)
                    .build());
        }
        chunks.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        if (chunks.size() > limit) {
            chunks = chunks.subList(0, limit);
        }

        long latency = System.currentTimeMillis() - startTime;
        log.info("图谱检索完成: kb={}, seeds={}, edges={}, chunks={}, {}ms",
                kbId, seeds.size(), expand.edges().size(), chunks.size(), latency);
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.GRAPH)
                .channelName(getName())
                .chunks(chunks)
                .latencyMs(latency)
                .build();
    }

    /** 构建三元组证据列表（同 chunk 内按 (s,p,t) 去重） */
    private List<Map<String, Object>> buildEvidence(List<GraphSubgraphExpander.GraphEdge> edges) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GraphSubgraphExpander.GraphEdge edge : edges) {
            String key = edge.sourceName() + "|" + edge.predicate() + "|" + edge.targetName();
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", edge.sourceName());
            item.put("predicate", edge.predicate());
            item.put("target", edge.targetName());
            if (StringUtils.hasText(edge.evidence())) {
                item.put("evidence", edge.evidence());
            }
            item.put("depth", edge.depth());
            evidence.add(item);
        }
        return evidence;
    }

    private String resolveKbId(String collectionName) {
        if (!StringUtils.hasText(collectionName)) {
            return null;
        }
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getCollectionName, collectionName)
                        .last("LIMIT 1"));
        return kb == null ? null : kb.getId();
    }

    private SearchChannelResult emptyResult(SearchContext context, long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.GRAPH)
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.GRAPH;
    }
}