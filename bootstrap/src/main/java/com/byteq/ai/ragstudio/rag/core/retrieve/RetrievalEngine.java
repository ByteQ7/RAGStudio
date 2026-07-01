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

    /**
     * 根据知识库 ID 列表执行检索
     * <p>
     * 通过知识库 ID 查询对应的向量集合名称，使用多通道检索引擎检索，
     * 将结果封装到 RetrievalContext 中。MCP 上下文由外部决策阶段控制调用。
     * </p>
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param rewriteResult    改写结果，包含改写后的问题和子问题列表
     * @param topK             期望返回的结果数量
     * @return 检索上下文，包含 KB 检索结果
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieveByKnowledgeBases(List<String> knowledgeBaseIds, RewriteResult rewriteResult, int topK) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return RetrievalContext.builder().build();
        }

        int finalTopK = topK > 0 ? topK : searchProperties.getDefaultTopK();

        // 1. KB 检索
        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .in(KnowledgeBaseDO::getId, knowledgeBaseIds)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
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
        String joinedKbNames = String.join(", ", kbNameMap.values());

        String kbContext = "";
        List<RetrievedChunk> chunks = List.of();
        if (CollUtil.isNotEmpty(collectionNames)) {
            List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                    ? rewriteResult.subQuestions()
                    : List.of(rewriteResult.rewrittenQuestion());
            chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(
                    collectionNames, subQuestions, rewriteResult.rewrittenQuestion(), finalTopK);
            if (CollUtil.isNotEmpty(chunks)) {
                // 为每个 Chunk 标记知识库名称
                for (RetrievedChunk chunk : chunks) {
                    if (chunk.getKbName() == null) {
                        chunk.setKbName(joinedKbNames);
                    }
                }
                // 批量查询文档名称
                java.util.List<String> chunkIds = chunks.stream()
                        .map(RetrievedChunk::getId)
                        .filter(Objects::nonNull)
                        .toList();
                if (CollUtil.isNotEmpty(chunkIds)) {
                    java.util.List<KnowledgeChunkDO> chunkDOs = knowledgeChunkMapper.selectList(
                            com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                                    .in(KnowledgeChunkDO::getId, chunkIds));
                    if (CollUtil.isNotEmpty(chunkDOs)) {
                        java.util.Set<String> docIds = chunkDOs.stream()
                                .map(KnowledgeChunkDO::getDocId)
                                .filter(Objects::nonNull)
                                .collect(java.util.stream.Collectors.toSet());
                        java.util.Map<String, String> docNameMap = new java.util.HashMap<>();
                        if (CollUtil.isNotEmpty(docIds)) {
                            java.util.List<KnowledgeDocumentDO> docs = knowledgeDocumentMapper.selectList(
                                    com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                                            .in(KnowledgeDocumentDO::getId, docIds));
                            for (KnowledgeDocumentDO doc : docs) {
                                docNameMap.put(doc.getId(), doc.getDocName());
                            }
                        }
                        java.util.Map<String, String> chunkDocMap = new java.util.HashMap<>();
                        for (KnowledgeChunkDO c : chunkDOs) {
                            String docName = docNameMap.get(c.getDocId());
                            if (docName != null) {
                                chunkDocMap.put(c.getId(), docName);
                            }
                        }
                        for (RetrievedChunk chunk : chunks) {
                            String dn = chunkDocMap.get(chunk.getId());
                            if (dn != null) {
                                chunk.setDocName(dn);
                            }
                        }
                    }
                }
                kbContext = contextFormatter.formatKbContext(Map.of(MULTI_CHANNEL_KEY, chunks), finalTopK);
            }
        }

        // 2. MCP 上下文（暂不启用自动调用，由决策阶段控制）
        String mcpContext = "";

        return RetrievalContext.builder()
                .mcpContext(mcpContext)
                .kbContext(kbContext)
                .chunks(chunks)
                .build();
    }
}
