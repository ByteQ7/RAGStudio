package com.byteq.ai.ragstudio.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.framework.trace.RagTraceNode;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeChunkDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.prompt.ContextFormatter;
import com.byteq.ai.ragstudio.rag.core.rewrite.RewriteResult;
import com.byteq.ai.ragstudio.rag.dto.RetrievalContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.byteq.ai.ragstudio.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）检索，并对检索结果进行格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final SearchChannelProperties searchProperties;
    private final ContextFormatter contextFormatter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final ImageChunkResolver imageChunkResolver;

    /**
     * 根据知识库 ID 列表执行检索
     * <p>
     * 通过知识库 ID 查询对应的向量集合名称，使用多通道检索引擎检索，
     * 将结果封装到 RetrievalContext 中。MCP 上下文由外部决策阶段控制调用。
     * </p>
     *
     * @param knowledgeBaseIds     知识库 ID 列表
     * @param rewriteResult        改写结果，包含改写后的问题和子问题列表
     * @param topK                 期望返回的结果数量
     * @param userOriginalQuestion 用户原始提问（未经改写，用于重排序阶段）
     * @return 检索上下文，包含 KB 检索结果
     */
    public RetrievalContext retrieveByKnowledgeBases(List<String> knowledgeBaseIds, RewriteResult rewriteResult,
                                                      int topK, String userOriginalQuestion) {
        return retrieveByKnowledgeBases(knowledgeBaseIds, rewriteResult, topK, userOriginalQuestion, 0);
    }

    /**
     * 根据知识库 ID 列表执行检索（带引用编号起始偏移）
     *
     * @param knowledgeBaseIds     知识库 ID 列表
     * @param rewriteResult        改写结果，包含改写后的问题和子问题列表
     * @param topK                 期望返回的结果数量
     * @param userOriginalQuestion 用户原始提问（未经改写，用于重排序阶段）
     * @param citationStartIndex   上下文引用编号起始偏移（Agent 多次检索时为已累计的 chunk 数，
     *                             保证 [^chunk_N] 编号跨检索全局唯一，引用溯源按位置精确映射）
     * @return 检索上下文，包含 KB 检索结果
     */
    public RetrievalContext retrieveByKnowledgeBases(List<String> knowledgeBaseIds, RewriteResult rewriteResult,
                                                      int topK, String userOriginalQuestion, int citationStartIndex) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return RetrievalContext.builder().chunks(List.of()).build();
        }

        List<RetrievedChunk> chunks = doRetrieve(knowledgeBaseIds, rewriteResult, topK, userOriginalQuestion);

        String kbContext = "";
        if (CollUtil.isNotEmpty(chunks)) {
            kbContext = contextFormatter.formatKbContext(Map.of(MULTI_CHANNEL_KEY, chunks), Integer.MAX_VALUE, citationStartIndex);
        }

        List<String> imageDataUris = imageChunkResolver.resolve(chunks);

        List<String> s3ImageUrls = chunks.stream()
                .filter(RetrievedChunk::isImage)
                .map(c -> c.getMetadata() != null ? c.getMetadata().get("image_url") : null)
                .filter(url -> url instanceof String)
                .map(Object::toString)
                .distinct()
                .toList();

        return RetrievalContext.builder()
                .mcpContext("")
                .kbContext(kbContext)
                .chunks(chunks)
                .imageDataUris(imageDataUris)
                .s3ImageUrls(s3ImageUrls)
                .build();
    }

    @RagTraceNode(name = "向量检索", type = "RETRIEVE")
    List<RetrievedChunk> doRetrieve(List<String> knowledgeBaseIds, RewriteResult rewriteResult,
                                    int topK, String userOriginalQuestion) {
        int finalTopK = topK > 0 ? topK : searchProperties.getDefaultTopK();

        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .in(KnowledgeBaseDO::getId, knowledgeBaseIds)
        );
        List<String> collectionNames = kbList.stream()
                .map(KnowledgeBaseDO::getCollectionName)
                .filter(Objects::nonNull)
                .filter(name -> !name.isBlank())
                .toList();

        // 构建 KB ID → KB 名称映射
        java.util.Map<String, String> kbNameMap = new java.util.HashMap<>();
        for (KnowledgeBaseDO kb : kbList) {
            kbNameMap.put(kb.getId(), kb.getName());
        }

        if (CollUtil.isEmpty(collectionNames)) {
            return List.of();
        }

        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(
                collectionNames, subQuestions, rewriteResult.rewrittenQuestion(), finalTopK, userOriginalQuestion);
        if (CollUtil.isEmpty(chunks)) {
            return chunks;
        }

        // 语义裁剪已通过后置处理器链在 Rerank 前执行（ContextCropper，order=5）
        // 此处仅做元数据富化（kbName/docName），并行执行以隐藏数据库查询时延。
        java.util.List<String> chunkIds = chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(Objects::nonNull)
                .toList();

        // 批量查询 Chunk 的 kbId 和 docId，设置所属知识库和文档名称
        try {
            if (CollUtil.isEmpty(chunkIds)) {
                return chunks;
            }

            java.util.List<KnowledgeChunkDO> chunkDOs = knowledgeChunkMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                            .in(KnowledgeChunkDO::getId, chunkIds));
            if (CollUtil.isEmpty(chunkDOs)) {
                return chunks;
            }

            // 构建 chunkId → kbName/docName/文档元数据映射
            Map<String, String> chunkKbNameMap = new HashMap<>();
            Map<String, String> chunkDocNameMap = new HashMap<>();
            Map<String, KnowledgeDocumentDO> chunkDocumentMap = new HashMap<>();
            // 获取所有涉及的文档 ID
            java.util.Set<String> docIds = chunkDOs.stream()
                    .map(KnowledgeChunkDO::getDocId)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            // 查询文档名称
            Map<String, KnowledgeDocumentDO> documentMap = new HashMap<>();
            if (CollUtil.isNotEmpty(docIds)) {
                java.util.List<KnowledgeDocumentDO> docs = knowledgeDocumentMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                                .in(KnowledgeDocumentDO::getId, docIds));
                for (KnowledgeDocumentDO doc : docs) {
                    documentMap.put(doc.getId(), doc);
                }
            }
            // 填充每个 chunk 的 kbName 和 docName
            for (KnowledgeChunkDO c : chunkDOs) {
                String kbName = kbNameMap.get(c.getKbId());
                if (kbName != null) {
                    chunkKbNameMap.put(c.getId(), kbName);
                }
                KnowledgeDocumentDO document = documentMap.get(c.getDocId());
                if (document != null) {
                    chunkDocumentMap.put(c.getId(), document);
                    if (document.getDocName() != null) {
                        chunkDocNameMap.put(c.getId(), document.getDocName());
                    }
                }
            }
            for (RetrievedChunk chunk : chunks) {
                String kn = chunkKbNameMap.get(chunk.getId());
                if (kn != null) {
                    chunk.setKbName(kn);
                }
                String dn = chunkDocNameMap.get(chunk.getId());
                if (dn != null) {
                    chunk.setDocName(dn);
                }
                KnowledgeDocumentDO document = chunkDocumentMap.get(chunk.getId());
                if (document != null) {
                    Map<String, Object> metadata = new HashMap<>();
                    if (chunk.getMetadata() != null) {
                        metadata.putAll(chunk.getMetadata());
                    }
                    if (document.getCreateTime() != null) {
                        metadata.put("document_created_at", document.getCreateTime().toInstant().toString());
                    }
                    if (document.getUpdateTime() != null) {
                        metadata.put("document_updated_at", document.getUpdateTime().toInstant().toString());
                    }
                    if (document.getSourceType() != null && !document.getSourceType().isBlank()) {
                        metadata.put("source_type", document.getSourceType());
                    }
                    chunk.setMetadata(metadata);
                }
            }
        } finally {
            // 无异步裁剪任务，直接返回
        }

        return chunks;
    }
}
