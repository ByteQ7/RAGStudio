package com.byteq.ai.ragstudio.ingestion.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.graph.service.GraphExtractionService;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.result.NodeResult;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 图谱抽取节点（GraphExtractorNode）
 * <p>
 * 数据摄入流水线的图谱构建节点：对 ChunkerNode 切分后的每个文本块调用 LLM
 * 抽取实体与关系，写入知识图谱（t_graph_entity / t_graph_relation）。
 * </p>
 * <p>
 * 放置要求：排在 {@link IndexerNode} 之后（chunkId 需已分配，索引节点在
 * skipIndexerWrite 模式下也会完成 chunkId 分配）。
 * </p>
 * <p>
 * 幂等性：抽取结果按 chunk 内容哈希缓存，重复执行时未变更 chunk 直接复用缓存，
 * 零额外 LLM 成本（见 {@link GraphExtractionService#extractForDocument}）。
 * </p>
 */
@Slf4j
@Component
public class GraphExtractorNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final GraphExtractionService graphExtractionService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public GraphExtractorNode(ObjectMapper objectMapper,
                              GraphExtractionService graphExtractionService,
                              KnowledgeBaseMapper knowledgeBaseMapper) {
        this.objectMapper = objectMapper;
        this.graphExtractionService = graphExtractionService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.GRAPH_EXTRACTOR.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (!graphExtractionService.isEnabled()) {
            return NodeResult.skip("图谱总开关未开启（rag.graph.enabled=false）");
        }
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("没有需要图谱抽取的文本块");
        }

        String collectionName = null;
        if (context.getVectorSpaceId() != null) {
            collectionName = context.getVectorSpaceId().getLogicalName();
        }
        if (!StringUtils.hasText(collectionName)) {
            return NodeResult.skip("无法确定知识库集合名称，跳过图谱抽取");
        }
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getCollectionName, collectionName)
                        .last("LIMIT 1"));
        if (kb == null) {
            return NodeResult.skip("知识库不存在（collection=" + collectionName + "），跳过图谱抽取");
        }
        String docId = context.getTaskId();
        if (!StringUtils.hasText(docId)) {
            return NodeResult.skip("缺少文档 ID（taskId），跳过图谱抽取");
        }

        GraphExtractionService.GraphExtractionReport report =
                graphExtractionService.extractForDocument(kb.getId(), docId, chunks, "DOC");
        if (report == null || report.skipped()) {
            return NodeResult.skip("图谱抽取跳过");
        }
        return NodeResult.ok(String.format("图谱抽取完成: 实体+%d(合并%d), 关系+%d, LLM调用%d, 失败chunk%d, 耗时%dms",
                report.entityAdded(), report.entityMerged(), report.relationAdded(),
                report.llmCalls(), report.failedChunks(), report.durationMs()));
    }

    /**
     * 解析节点配置（当前无配置项，保留方法供后续扩展）
     */
    @SuppressWarnings("unused")
    private JsonNode parseSettings(JsonNode node) {
        return node == null || node.isNull() ? objectMapper.nullNode() : node;
    }
}