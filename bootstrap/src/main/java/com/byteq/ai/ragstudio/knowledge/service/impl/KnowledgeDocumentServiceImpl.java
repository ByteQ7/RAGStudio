package com.byteq.ai.ragstudio.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.core.chunk.ChunkEmbeddingService;
import com.byteq.ai.ragstudio.core.chunk.ChunkingMode;
import com.byteq.ai.ragstudio.core.chunk.ChunkingOptions;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategy;
import com.byteq.ai.ragstudio.core.chunk.ChunkingStrategyFactory;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.core.parser.DocumentParserSelector;
import com.byteq.ai.ragstudio.core.parser.ParserType;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.mq.producer.MessageQueueProducer;
import com.byteq.ai.ragstudio.ingestion.dao.entity.IngestionPipelineDO;
import com.byteq.ai.ragstudio.ingestion.dao.mapper.IngestionPipelineMapper;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.dao.entity.IngestionTaskDO;
import com.byteq.ai.ragstudio.ingestion.dao.mapper.IngestionTaskMapper;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionStatus;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition;
import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import com.byteq.ai.ragstudio.ingestion.service.IngestionPipelineService;
import com.byteq.ai.ragstudio.knowledge.config.KnowledgeScheduleProperties;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeChunkVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentVO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentChunkLogDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.knowledge.enums.DocumentStatus;
import com.byteq.ai.ragstudio.knowledge.enums.ProcessMode;
import com.byteq.ai.ragstudio.knowledge.enums.SourceType;
import com.byteq.ai.ragstudio.knowledge.handler.RemoteFileFetcher;
import com.byteq.ai.ragstudio.knowledge.mq.event.KnowledgeDocumentChunkEvent;
import com.byteq.ai.ragstudio.knowledge.schedule.CronScheduleHelper;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeChunkService;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeDocumentScheduleService;
import com.byteq.ai.ragstudio.knowledge.service.DocumentVisionExtractor;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeDocumentService;
import com.byteq.ai.ragstudio.rag.core.vector.VectorSpaceId;
import com.byteq.ai.ragstudio.rag.core.vector.VectorStoreService;
import com.byteq.ai.ragstudio.rag.constant.RAGConstant;
import com.byteq.ai.ragstudio.rag.dto.StoredFileDTO;
import com.byteq.ai.ragstudio.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentParserSelector parserSelector;
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    private final FileStorageService fileStorageService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ObjectMapper objectMapper;
    private final KnowledgeDocumentScheduleService scheduleService;
    private final IngestionPipelineService ingestionPipelineService;
    private final IngestionPipelineMapper ingestionPipelineMapper;
    private final IngestionEngine ingestionEngine;
    private final ChunkEmbeddingService chunkEmbeddingService;
    private final KnowledgeDocumentChunkLogMapper chunkLogMapper;
    private final IngestionTaskMapper ingestionTaskMapper;
    private final TransactionOperations transactionOperations;
    private final MessageQueueProducer messageQueueProducer;
    private final KnowledgeScheduleProperties scheduleProperties;
    private final RemoteFileFetcher remoteFileFetcher;
    private final DocumentVisionExtractor documentVisionExtractor;

    @Value("knowledge-document-chunk_topic${unique-name:}")
    private String chunkTopic;

    /**
     * 上传文档
     * <p>
     * 处理流程：
     * 1. 校验知识库存在性
     * 2. 校验来源类型和定时调度参数
     * 3. 存储文件（本地上传或远程拉取）
     * 4. 解析处理模式配置并创建文档记录
     * </p>
     */
    @Override
    public KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在"));

        SourceType sourceType = SourceType.normalize(requestParam.getSourceType());
        validateSourceAndSchedule(sourceType, requestParam);
        String documentPrefix = RAGConstant.S3_DOCUMENT_PREFIX + "/" + kbDO.getCollectionName();
        StoredFileDTO stored = resolveStoredFile(RAGConstant.S3_BUCKET_NAME, documentPrefix, sourceType, requestParam.getSourceLocation(), file);
        ProcessModeConfig modeConfig = resolveProcessModeConfig(requestParam);

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .kbId(kbId)
                .docName(stored.getOriginalFilename())
                .enabled(1)
                .chunkCount(0)
                .fileUrl(stored.getUrl())
                .fileType(stored.getDetectedType())
                .fileSize(stored.getSize())
                .status(DocumentStatus.PENDING.getCode())
                .sourceType(sourceType.getValue())
                .sourceLocation(SourceType.URL == sourceType ? StrUtil.trimToNull(requestParam.getSourceLocation()) : null)
                .scheduleEnabled(isScheduleEnabled(sourceType, requestParam) ? 1 : 0)
                .scheduleCron(isScheduleEnabled(sourceType, requestParam) ? StrUtil.trimToNull(requestParam.getScheduleCron()) : null)
                .processMode(modeConfig.processMode().getValue())
                .chunkStrategy(modeConfig.chunkingMode() != null ? modeConfig.chunkingMode().getValue() : null)
                .chunkConfig(modeConfig.chunkConfig())
                .pipelineId(modeConfig.pipelineId())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        documentMapper.insert(documentDO);

        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    /**
     * 开始文档分块处理
     * <p>
     * 处理流程：
     * 1. 在事务中更新文档状态为 RUNNING（乐观锁防并发）
     * 2. 同步更新定时调度记录
     * 3. 发送 RocketMQ 事务消息触发异步分块
     * </p>
     */
    @Override
    public void startChunk(String docId) {
        KnowledgeDocumentChunkEvent event = KnowledgeDocumentChunkEvent.builder()
                .docId(docId)
                .operator(UserContext.getUsername())
                .build();

        messageQueueProducer.sendInTransaction(
                chunkTopic,
                docId,
                "文档分块",
                event,
                arg -> {
                    int updated = documentMapper.update(
                            new LambdaUpdateWrapper<KnowledgeDocumentDO>()
                                    .set(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                                    .set(KnowledgeDocumentDO::getUpdatedBy, event.getOperator())
                                    .eq(KnowledgeDocumentDO::getId, docId)
                                    .ne(KnowledgeDocumentDO::getStatus, DocumentStatus.RUNNING.getCode())
                    );
                    if (updated == 0) {
                        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
                        throw new ClientException("文档分块操作正在进行中，请稍后再试");
                    }
                    KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
                    event.setKbId(documentDO.getKbId());
                    scheduleService.upsertSchedule(documentDO);
                }
        );
    }

    @Override
    public void executeChunk(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        if (documentDO == null) {
            log.warn("文档不存在，跳过分块任务, docId={}", docId);
            return;
        }

        runChunkTask(documentDO);
    }

    // 执行文档分块任务的完整流程：创建日志 → 按处理模式执行分块 → 持久化结果 → 更新日志
    private void runChunkTask(KnowledgeDocumentDO documentDO) {
        String docId = documentDO.getId();
        ProcessMode processMode = ProcessMode.normalize(documentDO.getProcessMode());

        KnowledgeDocumentChunkLogDO chunkLog = KnowledgeDocumentChunkLogDO.builder()
                .docId(docId)
                .status(DocumentStatus.RUNNING.getCode())
                .processMode(processMode.getValue())
                .chunkStrategy(documentDO.getChunkStrategy())
                .pipelineId(documentDO.getPipelineId())
                .startTime(new Date())
                .build();
        chunkLogMapper.insert(chunkLog);

        long totalStartTime = System.currentTimeMillis();
        long extractDuration = 0;
        long chunkDuration = 0;
        long embedDuration = 0;
        long persistDuration = 0;

        try {
            List<VectorChunk> chunkResults;
            if (ProcessMode.PIPELINE == processMode) {
                long start = System.currentTimeMillis();
                chunkResults = runPipelineProcess(documentDO);
                chunkDuration = System.currentTimeMillis() - start;
            } else {
                ChunkProcessResult result = runChunkProcess(documentDO);
                extractDuration = result.extractDuration();
                chunkDuration = result.chunkDuration();
                embedDuration = result.embedDuration();
                chunkResults = result.chunks();
            }

            long persistStart = System.currentTimeMillis();
            String collectionName = resolveCollectionName(documentDO.getKbId());
            int savedCount = persistChunksAndVectors(collectionName, docId, chunkResults);
            persistDuration = System.currentTimeMillis() - persistStart;

            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.SUCCESS.getCode(), savedCount,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, null);
        } catch (Exception e) {
            log.error("文档分块任务执行失败：docId={}", docId, e);
            markChunkFailed(documentDO.getId());
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, e.getMessage());
        }
    }

    // 持久化分块结果：Phase1 在事务中删除旧分块、批量创建新分块并更新文档状态；Phase2 在事务外写入向量库
    private int persistChunksAndVectors(String collectionName, String docId, List<VectorChunk> chunkResults) {
        List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.getContent());
                    return req;
                })
                .toList();

        // Phase 1: Commit DB changes in a transaction
        transactionOperations.executeWithoutResult(status -> {
            knowledgeChunkService.deleteByDocId(docId);
            knowledgeChunkService.batchCreate(docId, chunks);
            KnowledgeDocumentDO updateDocumentDO = KnowledgeDocumentDO.builder()
                    .id(docId)
                    .chunkCount(chunks.size())
                    .status(DocumentStatus.SUCCESS.getCode())
                    .updatedBy(UserContext.getUsername())
                    .build();
            documentMapper.updateById(updateDocumentDO);
        });

        // Phase 2: Vector store operations outside DB transaction.
        // These cannot be rolled back by the DB transaction; if they fail, manual recovery is needed.
        try {
            vectorStoreService.deleteDocumentVectors(collectionName, docId);
            vectorStoreService.indexDocumentChunks(collectionName, docId, chunkResults);
        } catch (Exception e) {
            log.warn("向量存储操作失败，DB已提交，需要手动恢复向量数据。collectionName={}, docId={}, chunkCount={}",
                    collectionName, docId, chunks.size(), e);
        }

        return chunks.size();
    }

    // 更新分块日志记录，包含各阶段耗时和最终状态
    private void updateChunkLog(String logId, String status, int chunkCount, long extractDuration,
                                long chunkDuration, long embedDuration, long persistDuration,
                                long totalDuration, String errorMessage) {
        KnowledgeDocumentChunkLogDO update = KnowledgeDocumentChunkLogDO.builder()
                .id(logId)
                .status(status)
                .chunkCount(chunkCount)
                .extractDuration(extractDuration)
                .chunkDuration(chunkDuration)
                .embedDuration(embedDuration)
                .persistDuration(persistDuration)
                .totalDuration(totalDuration)
                .errorMessage(errorMessage)
                .endTime(new Date())
                .build();
        chunkLogMapper.updateById(update);
    }

    /**
     * 使用分块策略处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     * 4 阶段中的前 3 阶段：Extract → Chunk → Embed
     */
    private ChunkProcessResult runChunkProcess(KnowledgeDocumentDO documentDO) {
        ChunkingMode chunkingMode = ChunkingMode.fromValue(documentDO.getChunkStrategy());
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        ChunkingOptions config = buildChunkingOptions(chunkingMode, documentDO);
        String mimeType = documentDO.getFileType();

        long extractStart = System.currentTimeMillis();
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            // 优先提取 Markdown 格式（保留表格、标题等结构）
            String text = parserSelector.select(ParserType.TIKA.getType()).extractAsMarkdown(is, documentDO.getDocName());
            long extractDuration = System.currentTimeMillis() - extractStart;

            // 如果文档含嵌入图片（PDF/ODT/DOCX/PPTX），独立提取图片中的文字并追加
            if (documentVisionExtractor.mayContainEmbeddedImages(mimeType)) {
                String visionText = documentVisionExtractor.extractTextWithVision(
                        documentDO.getFileUrl(), mimeType, documentDO.getDocName());
                if (StrUtil.isNotBlank(visionText)) {
                    log.info("视觉提取成功: 从嵌入图片中获取到 {} 字符", visionText.length());
                    text = text + "\n\n" + visionText;
                }
            }
            // 纯图片文件无 Tika 文字，视觉提取结果是唯一来源
            if (mimeType != null && mimeType.startsWith("image/") && text.trim().isEmpty()) {
                String visionText = documentVisionExtractor.extractTextWithVision(
                        documentDO.getFileUrl(), mimeType, documentDO.getDocName());
                if (StrUtil.isNotBlank(visionText)) {
                    log.info("图片视觉提取成功: 获取到 {} 字符", visionText.length());
                    text = visionText;
                }
            }

            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(chunkingMode);
            long chunkStart = System.currentTimeMillis();
            List<VectorChunk> chunks = chunkingStrategy.chunk(text, config);
            long chunkDuration = System.currentTimeMillis() - chunkStart;

            long embedStart = System.currentTimeMillis();
            chunkEmbeddingService.embed(chunks, embeddingModel);
            long embedDuration = System.currentTimeMillis() - embedStart;

            return new ChunkProcessResult(chunks, extractDuration, chunkDuration, embedDuration);
        } catch (Exception e) {
            throw new RuntimeException("文档内容提取或分块失败", e);
        }
    }

    private record ChunkProcessResult(List<VectorChunk> chunks, long extractDuration, long chunkDuration,
                                      long embedDuration) {
    }

    private record ProcessModeConfig(ProcessMode processMode, ChunkingMode chunkingMode, String chunkConfig,
                                     String pipelineId) {
    }

    /**
     * 使用 Pipeline 处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     */
    private List<VectorChunk> runPipelineProcess(KnowledgeDocumentDO documentDO) {
        String docId = String.valueOf(documentDO.getId());
        String pipelineId = documentDO.getPipelineId();

        if (pipelineId == null) {
            throw new IllegalStateException("Pipeline模式下Pipeline ID为空：docId=" + docId);
        }

        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());

        PipelineDefinition pipelineDef = ingestionPipelineService.getDefinition(pipelineId);

        byte[] fileBytes;
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            fileBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件内容失败：docId=" + docId, e);
        }

        // 创建摄入任务记录，使其出现在“流水线任务”列表中
        String fileName = documentDO.getDocName();
        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(pipelineId)
                .sourceType(documentDO.getSourceType())
                .sourceLocation(documentDO.getFileUrl())
                .sourceFileName(fileName)
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        ingestionTaskMapper.insert(task);

        IngestionContext context = IngestionContext.builder()
                .taskId(docId)
                .pipelineId(pipelineId)
                .rawBytes(fileBytes)
                .mimeType(documentDO.getFileType())
                .vectorSpaceId(VectorSpaceId.builder()
                        .logicalName(kbDO.getCollectionName())
                        .build())
                .skipIndexerWrite(true)
                .build();

        IngestionContext result = ingestionEngine.execute(pipelineDef, context);

        if (result.getError() != null) {
            task.setStatus(IngestionStatus.FAILED.getValue());
            task.setErrorMessage(result.getError().getMessage());
            task.setCompletedAt(new Date());
            ingestionTaskMapper.updateById(task);
            throw new RuntimeException("Pipeline执行失败：" + result.getError().getMessage(), result.getError());
        }

        List<VectorChunk> chunks = result.getChunks();
        task.setStatus(IngestionStatus.COMPLETED.getValue());
        task.setChunkCount(chunks == null ? 0 : chunks.size());
        task.setCompletedAt(new Date());
        ingestionTaskMapper.updateById(task);

        if (chunks == null || chunks.isEmpty()) {
            log.warn("Pipeline执行完成但未产生分块：docId={}", docId);
            return List.of();
        }

        return chunks;
    }

    /**
     * 执行文档分块处理（由定时刷新处理器调用，不经过 MQ）
     */
    public void chunkDocument(KnowledgeDocumentDO documentDO) {
        if (documentDO == null) {
            return;
        }
        runChunkTask(documentDO);
    }

    // 在事务中将文档状态标记为分块失败（FAILED）
    private void markChunkFailed(String docId) {
        transactionOperations.executeWithoutResult(status -> {
            KnowledgeDocumentDO update = new KnowledgeDocumentDO();
            update.setId(docId);
            update.setStatus(DocumentStatus.FAILED.getCode());
            update.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(update);
        });
    }

    /**
     * 删除文档
     * <p>
     * 处理流程：
     * 1. 校验文档存在且未在分块中
     * 2. 删除关联的分块、调度、日志记录
     * 3. 逻辑删除文档
     * 4. 清理向量库数据和存储文件
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时删除
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法删除");
        }

        knowledgeChunkService.deleteByDocId(docId);
        scheduleService.deleteByDocId(docId);
        chunkLogMapper.delete(Wrappers.lambdaQuery(KnowledgeDocumentChunkLogDO.class)
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId));

        documentDO.setDeleted(1);
        documentDO.setUpdatedBy(UserContext.getUsername());
        documentMapper.deleteById(documentDO);

        String collectionName = resolveCollectionName(documentDO.getKbId());
        vectorStoreService.deleteDocumentVectors(collectionName, docId);
        deleteStoredFileQuietly(documentDO);
    }

    @Override
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        return BeanUtil.toBean(documentDO, KnowledgeDocumentVO.class);
    }

    /**
     * 更新文档信息
     * <p>
     * 处理流程：
     * 1. 校验文档存在且未在分块中
     * 2. 更新文档名称和处理模式（CHUNK/PIPELINE）
     * 3. 处理定时调度相关字段（仅 URL 类型文档，校验 cron 合法性）
     * 4. 若调度参数变更则同步更新调度记录
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, KnowledgeDocumentUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        String docName = requestParam == null ? null : requestParam.getDocName();
        if (!StringUtils.hasText(docName)) {
            throw new ClientException("文档名称不能为空");
        }

        LambdaUpdateWrapper<KnowledgeDocumentDO> updateWrapper = Wrappers.lambdaUpdate(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getId, documentDO.getId())
                .set(KnowledgeDocumentDO::getDocName, docName.trim())
                .set(KnowledgeDocumentDO::getUpdatedBy, UserContext.getUsername());

        // 如果传了 processMode，校验并更新处理配置
        if (StringUtils.hasText(requestParam.getProcessMode())) {
            ProcessMode processMode = ProcessMode.normalize(requestParam.getProcessMode());
            updateWrapper.set(KnowledgeDocumentDO::getProcessMode, processMode.getValue());

            if (ProcessMode.CHUNK == processMode) {
                ChunkingMode chunkingMode = ChunkingMode.fromValue(requestParam.getChunkStrategy());
                String chunkConfig = validateAndNormalizeChunkConfig(chunkingMode, requestParam.getChunkConfig());
                updateWrapper.set(KnowledgeDocumentDO::getChunkStrategy, chunkingMode.getValue());
                updateWrapper.setSql("chunk_config = CAST({0} AS jsonb)", chunkConfig);
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, null);
            } else {
                if (!StringUtils.hasText(requestParam.getPipelineId())) {
                    throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
                }
                try {
                    ingestionPipelineService.get(requestParam.getPipelineId());
                } catch (Exception e) {
                    throw new ClientException("指定的Pipeline不存在: " + requestParam.getPipelineId());
                }
                updateWrapper.set(KnowledgeDocumentDO::getPipelineId, requestParam.getPipelineId());
                updateWrapper.set(KnowledgeDocumentDO::getChunkStrategy, null);
                updateWrapper.set(KnowledgeDocumentDO::getChunkConfig, null);
            }
        }

        // 处理定时调度相关字段（仅 URL 类型文档支持）
        boolean scheduleChanged = false;
        if (SourceType.URL.getValue().equalsIgnoreCase(documentDO.getSourceType())) {
            String newSourceLocation = requestParam.getSourceLocation();
            Integer newScheduleEnabled = requestParam.getScheduleEnabled();
            String newScheduleCron = requestParam.getScheduleCron();

            if (StringUtils.hasText(newSourceLocation)) {
                updateWrapper.set(KnowledgeDocumentDO::getSourceLocation, newSourceLocation.trim());
                scheduleChanged = true;
            }
            if (newScheduleEnabled != null) {
                updateWrapper.set(KnowledgeDocumentDO::getScheduleEnabled, newScheduleEnabled);
                scheduleChanged = true;
            }
            if (StringUtils.hasText(newScheduleCron)) {
                try {
                    CronScheduleHelper.nextRunTime(newScheduleCron, new Date());
                    // 验证 cron 周期不能太短（与 upsertSchedule 保持一致）
                    if (CronScheduleHelper.isIntervalLessThan(newScheduleCron, new Date(), 60)) {
                        throw new ClientException("定时周期不能小于 60 秒");
                    }
                } catch (IllegalArgumentException e) {
                    throw new ClientException("定时表达式不合法: " + e.getMessage());
                }
                updateWrapper.set(KnowledgeDocumentDO::getScheduleCron, newScheduleCron.trim());
                scheduleChanged = true;
            }

            // 验证：启用定时拉取时必须有 cron 和 sourceLocation
            // 使用内存中已加载的 documentDO 构建"将变为"状态，避免读取 DB 中的陈旧数据
            if (scheduleChanged) {
                Integer finalEnabled = newScheduleEnabled != null ? newScheduleEnabled : documentDO.getScheduleEnabled();
                String finalCron = StringUtils.hasText(newScheduleCron) ? newScheduleCron.trim() : documentDO.getScheduleCron();
                String finalLocation = StringUtils.hasText(newSourceLocation) ? newSourceLocation.trim() : documentDO.getSourceLocation();

                if (finalEnabled != null && finalEnabled == 1) {
                    if (!StringUtils.hasText(finalCron)) {
                        throw new ClientException("启用定时拉取时必须设置定时表达式");
                    }
                    if (!StringUtils.hasText(finalLocation)) {
                        throw new ClientException("启用定时拉取时必须设置来源地址");
                    }
                }
            }
        }

        documentMapper.update(updateWrapper);

        if (scheduleChanged) {
            KnowledgeDocumentDO updated = documentMapper.selectById(docId);
            scheduleService.upsertSchedule(updated);
        }
    }

    // 分页查询文档列表，支持按关键词和状态筛选
    @Override
    public IPage<KnowledgeDocumentVO> page(String kbId, KnowledgeDocumentPageRequest requestParam) {
        Page<KnowledgeDocumentDO> pageParam = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        LambdaQueryWrapper<KnowledgeDocumentDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                .eq(KnowledgeDocumentDO::getKbId, kbId)
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(requestParam.getKeyword() != null && !requestParam.getKeyword().isBlank(), KnowledgeDocumentDO::getDocName, requestParam.getKeyword())
                .eq(requestParam.getStatus() != null && !requestParam.getStatus().isBlank(), KnowledgeDocumentDO::getStatus, requestParam.getStatus())
                .orderByDesc(KnowledgeDocumentDO::getCreateTime);

        return documentMapper.selectPage(pageParam, queryWrapper)
                .convert(each -> BeanUtil.toBean(each, KnowledgeDocumentVO.class));
    }

    /**
     * 搜索文档
     * <p>按关键词模糊搜索文档名称，并补充关联的知识库名称信息。</p>
     */
    @Override
    public List<KnowledgeDocumentSearchVO> search(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        int size = Math.min(Math.max(limit, 1), 20);
        Page<KnowledgeDocumentDO> mpPage = new Page<>(1, size);
        LambdaQueryWrapper<KnowledgeDocumentDO> qw = new LambdaQueryWrapper<KnowledgeDocumentDO>()
                .eq(KnowledgeDocumentDO::getDeleted, 0)
                .like(KnowledgeDocumentDO::getDocName, keyword)
                .orderByDesc(KnowledgeDocumentDO::getUpdateTime);

        IPage<KnowledgeDocumentDO> result = documentMapper.selectPage(mpPage, qw);
        List<KnowledgeDocumentSearchVO> records = result.getRecords().stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeDocumentSearchVO.class))
                .toList();
        if (records.isEmpty()) {
            return records;
        }

        Set<String> kbIds = new HashSet<>();
        for (KnowledgeDocumentSearchVO record : records) {
            if (record.getKbId() != null) {
                kbIds.add(record.getKbId());
            }
        }
        if (kbIds.isEmpty()) {
            return records;
        }

        List<KnowledgeBaseDO> bases = knowledgeBaseMapper.selectByIds(kbIds);
        Map<String, String> nameMap = new HashMap<>();
        if (bases != null) {
            for (KnowledgeBaseDO base : bases) {
                nameMap.put(base.getId(), base.getName());
            }
        }
        for (KnowledgeDocumentSearchVO record : records) {
            record.setKbName(nameMap.get(record.getKbId()));
        }
        return records;
    }

    /**
     * 启用/禁用文档
     * <p>
     * 处理流程：
     * 1. 校验文档存在、未在分块中且状态确实需要变更
     * 2. 启用时先在事务外执行向量嵌入（耗时操作）
     * 3. 在事务中更新文档状态、同步调度记录、更新分片状态、操作向量库
     * </p>
     */
    @Override
    public void enable(String docId, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        // 禁止在文档分块运行时修改
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法修改");
        }

        // 如果已经是目标状态，直接返回
        int targetEnabled = enabled ? 1 : 0;
        if (documentDO.getEnabled() != null && documentDO.getEnabled() == targetEnabled) {
            return;
        }

        // 提前查知识库，两个分支都需要，避免重复查询
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String collectionName = kbDO.getCollectionName();

        // 启用时：embed 耗时较长，在事务外提前执行，避免长事务占用连接
        List<VectorChunk> vectorChunks = null;
        if (enabled) {
            List<KnowledgeChunkVO> chunks = knowledgeChunkService.listByDocId(docId);
            vectorChunks = chunks.stream().map(each ->
                    VectorChunk.builder()
                            .chunkId(each.getId())
                            .content(each.getContent())
                            .index(each.getChunkIndex())
                            .build()
            ).toList();
            if (CollUtil.isEmpty(vectorChunks)) {
                log.warn("启用文档时未找到任何 Chunk，跳过向量重建，docId={}", docId);
                return;
            }
            chunkEmbeddingService.embed(vectorChunks, kbDO.getEmbeddingModel());
        }

        final List<VectorChunk> finalVectorChunks = vectorChunks;
        transactionOperations.executeWithoutResult(status -> {
            documentDO.setEnabled(targetEnabled);
            documentDO.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(documentDO);
            scheduleService.syncScheduleIfExists(documentDO);
            knowledgeChunkService.updateEnabledByDocId(docId, String.valueOf(kbDO.getId()), enabled);

            if (!enabled) {
                vectorStoreService.deleteDocumentVectors(collectionName, docId);
            } else {
                vectorStoreService.indexDocumentChunks(collectionName, docId, finalVectorChunks);
            }
        });
    }

    /**
     * 查询文档分块日志
     * <p>分页查询分块日志并补充 Pipeline 名称和"其他耗时"字段。</p>
     */
    @Override
    public IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page) {
        Page<KnowledgeDocumentChunkLogDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<KnowledgeDocumentChunkLogDO> qw = new LambdaQueryWrapper<KnowledgeDocumentChunkLogDO>()
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId)
                .orderByDesc(KnowledgeDocumentChunkLogDO::getCreateTime);

        IPage<KnowledgeDocumentChunkLogDO> result = chunkLogMapper.selectPage(mpPage, qw);

        List<KnowledgeDocumentChunkLogDO> records = result.getRecords();
        Map<String, String> pipelineNameMap = new HashMap<>();
        if (CollUtil.isNotEmpty(records)) {
            Set<String> pipelineIds = new HashSet<>();
            for (KnowledgeDocumentChunkLogDO record : records) {
                if (record.getPipelineId() != null) {
                    pipelineIds.add(record.getPipelineId());
                }
            }
            if (!pipelineIds.isEmpty()) {
                List<IngestionPipelineDO> pipelines = ingestionPipelineMapper.selectByIds(pipelineIds);
                if (CollUtil.isNotEmpty(pipelines)) {
                    for (IngestionPipelineDO pipeline : pipelines) {
                        pipelineNameMap.put(pipeline.getId(), pipeline.getName());
                    }
                }
            }
        }

        Page<KnowledgeDocumentChunkLogVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(records.stream().map(each -> {
            KnowledgeDocumentChunkLogVO vo = BeanUtil.toBean(each, KnowledgeDocumentChunkLogVO.class);
            if (each.getPipelineId() != null) {
                vo.setPipelineName(pipelineNameMap.get(each.getPipelineId()));
            }
            Long totalDuration = each.getTotalDuration();
            if (totalDuration != null) {
                long other = getOther(each, totalDuration);
                vo.setOtherDuration(Math.max(0, other));
            }
            return vo;
        }).toList());
        return voPage;
    }

    // 计算分块日志中"其他耗时"= 总耗时 - 各已知阶段耗时之和
    private static long getOther(KnowledgeDocumentChunkLogDO each, Long totalDuration) {
        String mode = each.getProcessMode();
        boolean pipelineMode = ProcessMode.PIPELINE.getValue().equalsIgnoreCase(mode);
        long extract = each.getExtractDuration() == null ? 0 : each.getExtractDuration();
        long chunk = each.getChunkDuration() == null ? 0 : each.getChunkDuration();
        long embed = each.getEmbedDuration() == null ? 0 : each.getEmbedDuration();
        long persist = each.getPersistDuration() == null ? 0 : each.getPersistDuration();
        return pipelineMode
                ? totalDuration - chunk - persist
                : totalDuration - extract - chunk - embed - persist;
    }

    // 根据知识库 ID 查询对应的向量集合名称
    private String resolveCollectionName(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null) {
            throw new ClientException("知识库不存在: " + kbId);
        }
        return kbDO.getCollectionName();
    }

    // 判断文档是否启用了定时同步（仅 URL 类型且 scheduleEnabled=true）
    private boolean isScheduleEnabled(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        return SourceType.URL == sourceType && Boolean.TRUE.equals(request.getScheduleEnabled());
    }

    // 校验来源地址和定时调度参数的合法性
    private void validateSourceAndSchedule(SourceType sourceType, KnowledgeDocumentUploadRequest request) {
        String sourceLocation = StrUtil.trimToNull(request.getSourceLocation());
        if (SourceType.URL == sourceType && !StringUtils.hasText(sourceLocation)) {
            throw new ClientException("来源地址不能为空");
        }
        if (!isScheduleEnabled(sourceType, request)) {
            return;
        }
        String scheduleCron = StrUtil.trimToNull(request.getScheduleCron());
        if (!StringUtils.hasText(scheduleCron)) {
            throw new ClientException("定时表达式不能为空");
        }
        try {
            if (CronScheduleHelper.isIntervalLessThan(scheduleCron, new java.util.Date(), scheduleProperties.getMinIntervalSeconds())) {
                throw new ClientException("定时周期不能小于 " + scheduleProperties.getMinIntervalSeconds() + " 秒");
            }
        } catch (IllegalArgumentException e) {
            throw new ClientException("定时表达式不合法");
        }
    }

    // 解析处理模式配置：CHUNK 模式解析分块策略和参数，PIPELINE 模式校验 Pipeline ID
    private ProcessModeConfig resolveProcessModeConfig(KnowledgeDocumentUploadRequest request) {
        ProcessMode processMode = ProcessMode.normalize(request.getProcessMode());
        if (ProcessMode.CHUNK == processMode) {
            ChunkingMode chunkingMode = ChunkingMode.fromValue(request.getChunkStrategy());
            String chunkConfig = validateAndNormalizeChunkConfig(chunkingMode, request.getChunkConfig());
            return new ProcessModeConfig(processMode, chunkingMode, chunkConfig, null);
        } else {
            if (!StringUtils.hasText(request.getPipelineId())) {
                throw new ClientException("使用Pipeline模式时，必须指定Pipeline ID");
            }
            try {
                ingestionPipelineService.get(request.getPipelineId());
            } catch (Exception e) {
                throw new ClientException("指定的Pipeline不存在: " + request.getPipelineId());
            }
            return new ProcessModeConfig(processMode, null, null, request.getPipelineId());
        }
    }

    // 根据来源类型解析文件存储：本地文件直接上传，远程 URL 通过 RemoteFileFetcher 拉取
    // 上传到统一桶 ragstudio 下的 document/ 目录
    private StoredFileDTO resolveStoredFile(String bucketName, String prefix, SourceType sourceType, String sourceLocation, MultipartFile file) {
        if (SourceType.FILE == sourceType) {
            Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
            return fileStorageService.upload(bucketName, prefix, file);
        }
        return remoteFileFetcher.fetchAndStore(bucketName, prefix, sourceLocation);
    }

    // 根据分块模式和文档的分块参数 JSON 构建分块配置选项
    private ChunkingOptions buildChunkingOptions(ChunkingMode mode, KnowledgeDocumentDO documentDO) {
        Map<String, Object> config = parseChunkConfig(documentDO.getChunkConfig());
        return mode.createOptions(config);
    }

    // 校验并归一化分块参数 JSON，确保包含所选分块模式的所有必要字段
    private String validateAndNormalizeChunkConfig(ChunkingMode mode, String chunkConfigJson) {
        if (!StringUtils.hasText(chunkConfigJson)) {
            return null;
        }
        if (mode == null) {
            mode = ChunkingMode.STRUCTURE_AWARE;
        }
        String json = chunkConfigJson.trim();
        Map<String, Object> config;
        try {
            config = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ClientException("分块参数JSON格式不合法");
        }
        for (String key : mode.getDefaultConfig().keySet()) {
            if (!config.containsKey(key)) {
                throw new ClientException("分块参数缺少必要字段: " + key);
            }
        }
        return json;
    }

    // 解析分块参数 JSON 为 Map，解析失败时返回空 Map
    private Map<String, Object> parseChunkConfig(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("分块参数解析失败: {}", json, e);
            return Map.of();
        }
    }

    // 安全删除文档关联的存储文件，失败时仅记录警告日志
    private void deleteStoredFileQuietly(KnowledgeDocumentDO documentDO) {
        if (documentDO == null || !StringUtils.hasText(documentDO.getFileUrl())) {
            return;
        }
        try {
            fileStorageService.deleteByUrl(documentDO.getFileUrl());
        } catch (Exception e) {
            log.warn("删除文档存储文件失败, docId={}, fileUrl={}", documentDO.getId(), documentDO.getFileUrl(), e);
        }
    }
}
