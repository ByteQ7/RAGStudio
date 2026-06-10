package com.byteq.ai.ragstudio.rag.eval;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeChunkDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.byteq.ai.ragstudio.rag.config.SearchChannelProperties;
import com.byteq.ai.ragstudio.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.byteq.ai.ragstudio.rag.core.rewrite.QueryRewriteService;
import com.byteq.ai.ragstudio.rag.core.rewrite.RewriteResult;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 效果评测接口
 * <p>
 * 仅运行 {@code rewrite → retrieve} 两步，不走 LLM，不污染对话表 / trace 表。
 * 评测项目调本接口取检索证据（docIds / chunkIds / contexts），LLM 输出通过 /rag/v3/chat 单独取。
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.eval", name = "enabled", havingValue = "true")
public class EvalController {

    private final QueryRewriteService queryRewriteService;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final SearchChannelProperties searchProperties;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    @GetMapping("/rag/eval")
    public Result<EvalResponse> chat(@RequestParam String question,
                                     @RequestParam(required = false) List<String> collectionNames) {
        long start = System.currentTimeMillis();

        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, List.of());

        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());

        List<String> collections = CollUtil.isEmpty(collectionNames) ? List.of() : collectionNames;
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(
                collections, subQuestions, rewriteResult.rewrittenQuestion(), searchProperties.getDefaultTopK());

        return Results.success(buildResponse(chunks, System.currentTimeMillis() - start));
    }

    private EvalResponse buildResponse(List<RetrievedChunk> chunks, long latencyMs) {
        List<RetrievedChunk> uniqueChunks = deduplicateChunks(chunks);
        List<String> chunkIds = uniqueChunks.stream()
                .map(RetrievedChunk::getId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
        List<String> contexts = uniqueChunks.stream()
                .map(RetrievedChunk::getText)
                .collect(Collectors.toList());
        List<String> docIds = lookupDocIds(chunkIds);

        boolean hasKb = CollUtil.isNotEmpty(uniqueChunks);

        return EvalResponse.builder()
                .retrievedDocIds(docIds)
                .retrievedChunkIds(chunkIds)
                .retrievedContexts(contexts)
                .hasKb(hasKb)
                .latencyMs(latencyMs)
                .build();
    }

    /**
     * 按 chunk id 去重并保留首次顺序
     */
    private List<RetrievedChunk> deduplicateChunks(List<RetrievedChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return Collections.emptyList();
        }
        Set<String> seen = new LinkedHashSet<>();
        return chunks.stream()
                .filter(c -> c != null && StrUtil.isNotBlank(c.getId()))
                .filter(c -> seen.add(c.getId()))
                .collect(Collectors.toList());
    }

    /**
     * 通过 chunkId 反查 t_knowledge_chunk.docId，**保持输入 chunkIds 的顺序**后再按 docId 去重保留首次出现。
     * <p>
     * 注意：{@link com.baomidou.mybatisplus.core.mapper.BaseMapper#selectByIds(java.util.Collection)}
     * 生成的 SQL 形如 {@code WHERE id IN (...)}，DB 按主键物理顺序返回行，不保证与传入顺序一致。
     * 若直接 stream 取 docId，会让 retrievedDocIds 与 retrievedChunkIds / retrievedContexts 错位，
     * 进而污染评测项目里基于排名的 Hit@1 / Hit@3 / MRR 等指标。这里显式按 chunkIds 重排后再 dedupe。
     */
    private List<String> lookupDocIds(List<String> chunkIds) {
        if (CollUtil.isEmpty(chunkIds)) {
            return Collections.emptyList();
        }
        List<KnowledgeChunkDO> chunks = knowledgeChunkMapper.selectByIds(chunkIds);
        if (CollUtil.isEmpty(chunks)) {
            return Collections.emptyList();
        }
        Map<String, String> chunkIdToDocId = chunks.stream()
                .filter(c -> StrUtil.isNotBlank(c.getId()) && StrUtil.isNotBlank(c.getDocId()))
                .collect(Collectors.toMap(
                        KnowledgeChunkDO::getId,
                        KnowledgeChunkDO::getDocId,
                        (a, b) -> a));
        Set<String> seen = new LinkedHashSet<>();
        return chunkIds.stream()
                .map(chunkIdToDocId::get)
                .filter(StrUtil::isNotBlank)
                .filter(seen::add)
                .collect(Collectors.toList());
    }
}
