package com.byteq.ai.ragstudio.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeChunkVO;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeChunkDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import com.byteq.ai.ragstudio.infra.token.TokenCounterService;
import com.byteq.ai.ragstudio.knowledge.enums.DocumentStatus;
import com.byteq.ai.ragstudio.rag.core.vector.VectorStoreService;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;

import cn.hutool.crypto.SecureUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库 Chunk 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingService embeddingService;
    private final TokenCounterService tokenCounterService;
    private final VectorStoreService vectorStoreService;
    private final TransactionOperations transactionOperations;

    // 按文档 ID 分页查询分片列表，按分片序号升序排列
    @Override
    public IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        LambdaQueryWrapper<KnowledgeChunkDO> queryWrapper = new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(requestParam.getEnabled() != null, KnowledgeChunkDO::getEnabled, requestParam.getEnabled())
                .orderByAsc(KnowledgeChunkDO::getChunkIndex);

        Page<KnowledgeChunkDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeChunkDO> result = chunkMapper.selectPage(page, queryWrapper);
        return result.convert(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class));
    }

    /**
     * 新增分片
     * <p>
     * 处理流程：校验文档状态 → 自动计算分片序号 → 创建分片记录 →
     * 更新文档分片计数 → 同步写入向量库
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持新增 Chunk");
        }
        if (!Integer.valueOf(1).equals(documentDO.getEnabled())) {
            throw new ClientException("文档未启用，暂不支持新增 Chunk");
        }

        String content = requestParam.getContent();
        Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));

        KnowledgeChunkDO latest = chunkMapper.selectOne(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .orderByDesc(KnowledgeChunkDO::getChunkIndex)
                        .last("LIMIT 1")
        );
        int chunkIndex = requestParam.getIndex() != null
                ? requestParam.getIndex()
                : (latest != null ? latest.getChunkIndex() + 1 : 0);

        String contentHash = SecureUtil.sha256(content);
        int charCount = content.length();
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        String collectionName = kbDO.getCollectionName();
        Integer tokenCount = resolveTokenCount(content);

        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id(requestParam.getChunkId())
                .kbId(documentDO.getKbId())
                .docId(docId)
                .chunkIndex(chunkIndex)
                .content(content)
                .contentHash(contentHash)
                .charCount(charCount)
                .tokenCount(tokenCount)
                .enabled(1)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();

        chunkMapper.insert(chunkDO);
        log.info("新增 Chunk 成功, kbId={}, docId={}, chunkId={}, chunkIndex={}", documentDO.getKbId(), docId, chunkDO.getId(), chunkIndex);

        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId)
                .setSql("chunk_count = chunk_count + 1"));

        // 同步写入向量库
        syncChunkToVector(collectionName, docId, chunkDO, embeddingModel);

        return BeanUtil.toBean(chunkDO, KnowledgeChunkVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams) {
        batchCreate(docId, requestParams, false);
    }

    /**
     * 批量新增分片
     * <p>
     * 处理流程：自动计算缺失的分片序号 → 逐条创建分片记录 →
     * 批量写入数据库 → 更新文档分片计数 → 可选同步写入向量库
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams, boolean writeVector) {
        if (CollUtil.isEmpty(requestParams)) {
            return;
        }

        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        boolean needAutoIndex = requestParams.stream().anyMatch(request -> request.getIndex() == null);
        int nextIndex = 0;
        if (needAutoIndex) {
            KnowledgeChunkDO latest = chunkMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeChunkDO>()
                            .eq(KnowledgeChunkDO::getDocId, docId)
                            .orderByDesc(KnowledgeChunkDO::getChunkIndex)
                            .last("LIMIT 1")
            );
            nextIndex = latest != null && latest.getChunkIndex() != null ? latest.getChunkIndex() + 1 : 0;
        }

        String kbId = documentDO.getKbId();
        String username = UserContext.getUsername();
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        String embeddingModel = kbDO.getEmbeddingModel();
        String collectionName = kbDO.getCollectionName();
        List<KnowledgeChunkDO> chunkDOList = new ArrayList<>(requestParams.size());

        for (KnowledgeChunkCreateRequest request : requestParams) {
            String content = request.getContent();
            Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));

            Integer chunkIndex = request.getIndex();
            if (chunkIndex == null) {
                chunkIndex = nextIndex++;
            }

            String chunkId = request.getChunkId();
            if (!StringUtils.hasText(chunkId)) {
                chunkId = IdUtil.getSnowflakeNextIdStr();
            }

            KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                    .id(chunkId)
                    .kbId(kbId)
                    .docId(docId)
                    .chunkIndex(chunkIndex)
                    .content(content)
                    .contentHash(SecureUtil.sha256(content))
                    .charCount(content.length())
                    .tokenCount(resolveTokenCount(content))
                    .enabled(1)
                    .createdBy(username)
                    .updatedBy(username)
                    .build();
            chunkDOList.add(chunkDO);
        }

        // 批量写入数据库，向量索引由上层统一处理以避免重复计算
        for (KnowledgeChunkDO chunkDO : chunkDOList) {
            chunkMapper.insert(chunkDO);
        }

        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId)
                .setSql("chunk_count = chunk_count + " + chunkDOList.size()));

        if (writeVector) {
            List<VectorChunk> vectorChunks = chunkDOList.stream()
                    .map(each -> VectorChunk.builder()
                            .chunkId(String.valueOf(each.getId()))
                            .content(each.getContent())
                            .index(each.getChunkIndex())
                            .build())
                    .toList();
            if (CollUtil.isNotEmpty(vectorChunks)) {
                attachEmbeddings(vectorChunks, embeddingModel);
                vectorStoreService.indexDocumentChunks(collectionName, docId, vectorChunks);
            }
        }
    }

    /**
     * 更新分片内容
     * <p>校验文档和分片存在性后更新内容、哈希值和 Token 计数，并同步到向量库。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持修改 Chunk");
        }

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));

        String newContent = requestParam.getContent();
        Assert.notBlank(newContent, () -> new ClientException("Chunk 内容不能为空"));

        if (newContent.equals(chunkDO.getContent())) {
            return;
        }

        chunkDO.setContent(newContent);
        chunkDO.setContentHash(SecureUtil.sha256(newContent));
        chunkDO.setCharCount(newContent.length());
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        String collectionName = kbDO.getCollectionName();
        chunkDO.setTokenCount(resolveTokenCount(newContent));
        chunkDO.setUpdatedBy(UserContext.getUsername());

        chunkMapper.updateById(chunkDO);

        log.info("更新 Chunk 成功, kbId={}, docId={}, chunkId={}", documentDO.getKbId(), docId, chunkId);

        // 同步向量数据库
        vectorStoreService.updateChunk(
                collectionName,
                docId,
                VectorChunk.builder()
                        .chunkId(chunkId)
                        .content(newContent)
                        .index(chunkDO.getChunkIndex())
                        .embedding(toArray(embedContent(newContent, embeddingModel)))
                        .build()
        );
    }

    /**
     * 删除分片
     * <p>删除分片记录、更新文档分片计数并从向量库中移除该分片。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId, String chunkId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持删除 Chunk");
        }

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        Assert.notNull(kbDO, () -> new ServiceException("知识库不存在"));
        String collectionName = kbDO.getCollectionName();

        chunkMapper.deleteById(chunkId);

        documentMapper.update(Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, docId)
                .setSql("chunk_count = CASE WHEN chunk_count > 0 THEN chunk_count - 1 ELSE 0 END"));

        log.info("删除 Chunk 成功, kbId={}, docId={}, chunkId={}", documentDO.getKbId(), docId, chunkId);

        deleteChunkFromVector(collectionName, chunkId);
    }

    /**
     * 启用/禁用单个分片
     * <p>启用时将分片重新写入向量库，禁用时从向量库移除。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableChunk(String docId, String chunkId, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持修改 Chunk 状态");
        }
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(docId), () -> new ClientException("Chunk 不属于该文档"));

        // 如果状态没变，直接返回
        int enabledValue = enabled ? 1 : 0;
        if (Integer.valueOf(enabledValue).equals(chunkDO.getEnabled())) {
            return;
        }

        chunkDO.setEnabled(enabledValue);
        chunkDO.setUpdatedBy(UserContext.getUsername());
        chunkMapper.updateById(chunkDO);

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();
        log.info("{}Chunk 成功, kbId={}, docId={}, chunkId={}", enabled ? "启用" : "禁用", documentDO.getKbId(), docId, chunkId);

        if (enabled) {
            String embeddingModel = kbDO.getEmbeddingModel();
            syncChunkToVector(collectionName, docId, chunkDO, embeddingModel);
        } else {
            deleteChunkFromVector(collectionName, chunkId);
        }
    }

    /**
     * 批量启用/禁用分片
     * <p>
     * 处理流程：校验请求参数和文档状态 → 过滤出状态需要变更的分片 →
     * 启用时批量嵌入向量并在事务中更新 DB 和向量库，禁用时在事务中更新 DB 并删除向量
     * </p>
     */
    @Override
    public void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled) {
        if (requestParam == null || CollUtil.isEmpty(requestParam.getChunkIds())) {
            throw new ClientException("请指定需要操作的 Chunk，全量启用/禁用请使用文档启用接口");
        }
        List<String> requestedIds = requestParam.getChunkIds();
        if (requestedIds.size() > 500) {
            throw new ClientException("单次批量操作 Chunk 数量不能超过 500");
        }

        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块处理中，暂不支持批量修改 Chunk 状态");
        }
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        List<KnowledgeChunkDO> found = chunkMapper.selectByIds(requestedIds);
        if (found.size() != requestedIds.size()) {
            throw new ClientException("存在无效的 Chunk ID，请求 " + requestedIds.size() + " 个，实际找到 " + found.size() + " 个");
        }
        found.forEach(c -> {
            if (!c.getDocId().equals(docId)) {
                throw new ClientException("Chunk " + c.getId() + " 不属于文档 " + docId);
            }
        });
        List<String> targetIds = found.stream().map(KnowledgeChunkDO::getId).collect(Collectors.toList());

        if (CollUtil.isEmpty(targetIds)) {
            return;
        }

        int enabledValue = enabled ? 1 : 0;
        List<KnowledgeChunkDO> needUpdateChunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .in(KnowledgeChunkDO::getId, targetIds)
                        .ne(KnowledgeChunkDO::getEnabled, enabledValue)
        );
        List<String> needUpdateIds = needUpdateChunks.stream().map(KnowledgeChunkDO::getId).collect(Collectors.toList());

        if (CollUtil.isEmpty(needUpdateIds)) {
            // 所有 Chunk 已处于目标状态，静默返回（与单条 enableChunk 行为一致）
            return;
        }

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();

        if (enabled) {
            List<VectorChunk> vectorChunks = needUpdateChunks.stream()
                    .map(c -> VectorChunk.builder()
                            .chunkId(c.getId())
                            .content(c.getContent())
                            .index(c.getChunkIndex())
                            .build())
                    .collect(Collectors.toList());
            attachEmbeddings(vectorChunks, kbDO.getEmbeddingModel());

            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(
                        Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                                .in(KnowledgeChunkDO::getId, needUpdateIds)
                                .set(KnowledgeChunkDO::getEnabled, 1)
                                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                );
                vectorStoreService.indexDocumentChunks(collectionName, docId, vectorChunks);
            });
        } else {
            transactionOperations.executeWithoutResult(status -> {
                chunkMapper.update(
                        Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                                .in(KnowledgeChunkDO::getId, needUpdateIds)
                                .set(KnowledgeChunkDO::getEnabled, 0)
                                .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
                );
                vectorStoreService.deleteChunksByIds(collectionName, needUpdateIds);
            });
        }

        log.info("批量{}Chunk 成功, kbId={}, docId={}, count={}", enabled ? "启用" : "禁用",
                documentDO.getKbId(), docId, needUpdateIds.size());
    }

    // 根据文档 ID 批量更新所有分片的启用状态（由文档启用/禁用时调用）
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabledByDocId(String docId, String kbId, boolean enabled) {
        int enabledValue = enabled ? 1 : 0;
        chunkMapper.update(
                Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .set(KnowledgeChunkDO::getEnabled, enabledValue)
                        .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
        );
        log.info("根据文档ID更新所有Chunk启用状态, kbId={}, docId={}, enabled={}", kbId, docId, enabled);
    }

    // 根据文档 ID 查询所有分片列表，按分片序号升序排列
    @Override
    public List<KnowledgeChunkVO> listByDocId(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        List<KnowledgeChunkDO> chunkDOList = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );

        return chunkDOList.stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class))
                .collect(Collectors.toList());
    }

    // 根据文档 ID 删除该文档下的所有分片记录
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        if (docId == null) {
            return;
        }
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId));
    }

    // ==================== 私有方法 ====================

    /**
     * 启用 chunk 前必须保证所属文档为启用状态
     */
    private void validateDocumentEnabledForChunkEnable(KnowledgeDocumentDO documentDO, boolean enableChunk) {
        if (!enableChunk) {
            return;
        }
        if (!Integer.valueOf(1).equals(documentDO.getEnabled())) {
            throw new ClientException("文档未启用，无法启用Chunk，请先启用文档");
        }
    }

    /**
     * 将单个 chunk 同步到向量库
     */
    private void syncChunkToVector(String collectionName, String docId, KnowledgeChunkDO chunkDO, String embeddingModel) {
        List<Float> embedding = embedContent(chunkDO.getContent(), embeddingModel);
        float[] vector = toArray(embedding);

        VectorChunk chunk = VectorChunk.builder()
                .index(chunkDO.getChunkIndex())
                .content(chunkDO.getContent())
                .chunkId(String.valueOf(chunkDO.getId()))
                .embedding(vector)
                .build();
        vectorStoreService.indexDocumentChunks(collectionName, docId, List.of(chunk));

        log.debug("同步 Chunk 到向量库成功, collectionName={}, docId={}, chunkId={}", collectionName, docId, chunkDO.getId());
    }

    /**
     * 从向量库删除单个 chunk
     */
    private void deleteChunkFromVector(String collectionName, String chunkId) {
        vectorStoreService.deleteChunkById(collectionName, chunkId);
        log.debug("从向量库删除 Chunk, collectionName={}, chunkId={}", collectionName, chunkId);
    }

    /**
     * List<Float> 转 float[]
     */
    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    // 批量为分片生成向量嵌入并设置到 VectorChunk 对象中
    private void attachEmbeddings(List<VectorChunk> chunks, String embeddingModel) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        List<String> texts = chunks.stream().map(VectorChunk::getContent).toList();
        List<List<Float>> vectors = embedBatch(texts, embeddingModel);
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new ServiceException("向量结果数量不匹配");
        }
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(toArray(vectors.get(i)));
        }
    }

    // 对单条文本进行向量嵌入，支持指定嵌入模型或使用默认模型
    private List<Float> embedContent(String content, String embeddingModel) {
        return StrUtil.isBlank(embeddingModel)
                ? embeddingService.embed(content)
                : embeddingService.embed(content, embeddingModel);
    }

    // 批量对文本列表进行向量嵌入
    private List<List<Float>> embedBatch(List<String> texts, String embeddingModel) {
        return StrUtil.isBlank(embeddingModel)
                ? embeddingService.embedBatch(texts)
                : embeddingService.embedBatch(texts, embeddingModel);
    }

    // 计算文本的 Token 数量
    private Integer resolveTokenCount(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        return tokenCounterService.countTokens(content);
    }
}
