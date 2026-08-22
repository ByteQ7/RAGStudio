package com.byteq.ai.ragstudio.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
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
import com.byteq.ai.ragstudio.core.chunk.ImageChunkGenerator;
import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.core.parser.DocumentParser;
import com.byteq.ai.ragstudio.core.parser.ParseEngine;
import com.byteq.ai.ragstudio.core.parser.ParseEngineResolver;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
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
import com.byteq.ai.ragstudio.graph.service.GraphExtractionService;
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
import com.byteq.ai.ragstudio.knowledge.enums.KnowledgeErrorCode;
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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ParseEngineResolver parseEngineResolver;
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
    private final ImageChunkGenerator imageChunkGenerator;
    private final GraphExtractionService graphExtractionService;

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
        Assert.notNull(kbDO, () -> new ClientException("知识库不存在", KnowledgeErrorCode.KB_NOT_FOUND));

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
                .parseEngine(StrUtil.isNotBlank(requestParam.getParseEngine())
                        ? ParseEngine.normalize(requestParam.getParseEngine()).getValue() : null)
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
                        Assert.notNull(documentDO, () -> new ClientException("文档不存在", KnowledgeErrorCode.DOCUMENT_NOT_FOUND));
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

        long totalStartTime = System.currentTimeMillis();
        long extractDuration = 0;
        long chunkDuration = 0;
        long embedDuration = 0;
        long persistDuration = 0;
        KnowledgeDocumentChunkLogDO chunkLog = null;

        try {
            ProcessMode processMode = ProcessMode.normalize(documentDO.getProcessMode());

            chunkLog = KnowledgeDocumentChunkLogDO.builder()
                    .docId(docId)
                    .status(DocumentStatus.RUNNING.getCode())
                    .processMode(processMode.getValue())
                    .chunkStrategy(documentDO.getChunkStrategy())
                    .pipelineId(documentDO.getPipelineId())
                    .startTime(new Date())
                    .build();
            chunkLogMapper.insert(chunkLog);
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
            int dimension = resolveDimension(documentDO.getKbId());
            int savedCount = persistChunksAndVectors(collectionName, docId, dimension, chunkResults);
            persistDuration = System.currentTimeMillis() - persistStart;

            long totalDuration = System.currentTimeMillis() - totalStartTime;
            updateChunkLog(chunkLog.getId(), DocumentStatus.SUCCESS.getCode(), savedCount,
                    extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, null);
        } catch (Exception e) {
            log.error("文档分块任务执行失败：docId={}", docId, e);
            markChunkFailed(documentDO.getId());
            long totalDuration = System.currentTimeMillis() - totalStartTime;
            if (chunkLog != null) {
                updateChunkLog(chunkLog.getId(), DocumentStatus.FAILED.getCode(), 0,
                        extractDuration, chunkDuration, embedDuration, persistDuration, totalDuration, e.getMessage());
            }
        }
    }

    // 持久化分块结果：Phase1 在事务中删除旧分块、批量创建新分块并更新文档状态；Phase2 在事务外写入向量库
    private int persistChunksAndVectors(String collectionName, String docId, int dimension, List<VectorChunk> chunkResults) {
        // 使用实际 embedding 维度（多模态模型可能返回与 KB 配置不同的维度）
        int actualDim = dimension;
        if (chunkResults != null && !chunkResults.isEmpty()) {
            VectorChunk first = chunkResults.get(0);
            if (first.getEmbedding() != null && first.getEmbedding().length != dimension) {
                actualDim = first.getEmbedding().length;
                log.warn("Embedding 实际维度 {} 与 KB 配置维度 {} 不一致，使用实际维度入库", actualDim, dimension);
            }
        }
        final int effectiveDim = actualDim;
        List<KnowledgeChunkCreateRequest> chunks = chunkResults.stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.isImage() ? "[图片]" : vc.getContent());
                    req.setContentType(vc.isImage() ? "IMAGE" : "TEXT");
                    if (vc.isImage() && vc.getMetadata() != null) {
                        Object imageUrl = vc.getMetadata().get("image_url");
                        if (imageUrl instanceof String url && !url.isBlank()) {
                            req.setImageUrl(url);
                        }
                    }
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
        // Retry up to 3 times if vector store operations fail.
        boolean vectorPersisted = false;
        int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // 同时清理配置维度与实际维度表：历史数据可能因维度漂移落在其他维度表，
                // 仅删目标表会残留旧向量（KB 切回旧维度时污染检索结果）
                if (effectiveDim != dimension) {
                    vectorStoreService.deleteDocumentVectors(collectionName, docId, dimension);
                }
                vectorStoreService.deleteDocumentVectors(collectionName, docId, effectiveDim);
                vectorStoreService.indexDocumentChunks(collectionName, docId, effectiveDim, chunkResults);
                vectorPersisted = true;
                break;
            } catch (Exception e) {
                log.warn("向量存储操作失败（第{}/{}次），collectionName={}, docId={}, chunkCount={}",
                        attempt, maxRetries, collectionName, docId, chunks.size(), e);
                if (attempt == maxRetries) {
                    log.error("向量存储操作重试{}次后仍然失败，DB已提交，需要手动恢复向量数据。collectionName={}, docId={}",
                            maxRetries, collectionName, docId);
                } else {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 向量写入最终失败时，将文档状态回退为 FAILED 并保留分块记录，
        // 用户可感知失败并通过重新分块重试（避免 DB 显示 SUCCESS 但检索静默丢文档）
        if (!vectorPersisted) {
            KnowledgeDocumentDO failedDocumentDO = KnowledgeDocumentDO.builder()
                    .id(docId)
                    .status(DocumentStatus.FAILED.getCode())
                    .updatedBy(UserContext.getUsername())
                    .build();
            documentMapper.updateById(failedDocumentDO);
            log.error("向量存储写入失败，文档状态已置为 FAILED: docId={}", docId);
            return chunks.size();
        }

        // 图谱增量抽取（异步，不阻塞分块链路）：未变更 chunk 复用抽取缓存，零 LLM 成本
        try {
            KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeBaseDO>()
                            .eq(KnowledgeBaseDO::getCollectionName, collectionName)
                            .last("LIMIT 1"));
            if (kbDO != null) {
                graphExtractionService.extractForDocumentAsync(kbDO.getId(), docId, chunkResults);
            }
        } catch (Exception e) {
            log.warn("触发图谱抽取失败（不影响分块结果）: docId={}, error={}", docId, e.getMessage());
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
     * 按解析引擎配置决策实际使用的文档解析器
     * <p>
     * 合并知识库级（{@code t_knowledge_base.parse_engine}）与文档级
     * （{@code t_knowledge_document.parse_engine}）配置，交由
     * {@link ParseEngineResolver} 决策出实际解析器（MinerU / Tika / 多模态兜底）。
     * </p>
     */
    private DocumentParser resolveParser(KnowledgeBaseDO kbDO, KnowledgeDocumentDO documentDO) {
        ParseEngine kbEngine = kbDO.getParseEngine() == null
                ? ParseEngine.AUTO : ParseEngine.normalize(kbDO.getParseEngine());
        ParseEngine docEngine = documentDO.getParseEngine() == null
                ? null : ParseEngine.normalize(documentDO.getParseEngine());
        String mimeType = documentDO.getFileType();
        return parseEngineResolver.resolveParser(kbEngine, docEngine, mimeType);
    }

    /**
     * 使用分块策略处理文档，失败直接抛异常，由 runChunkTask 统一处理错误状态
     * 4 阶段中的前 3 阶段：Extract → Chunk → Embed
     */
    private ChunkProcessResult runChunkProcess(KnowledgeDocumentDO documentDO) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(documentDO.getKbId());
        String embeddingModel = kbDO.getEmbeddingModel();
        String mimeType = documentDO.getFileType();
        boolean supportsImageEmbedding = kbDO.getSupportsImageEmbedding() != null && kbDO.getSupportsImageEmbedding() == 1;

        // 多模态 KB + 可被多模态处理的文件类型 → 走新流程
        if (supportsImageEmbedding && isMultimodalFileType(mimeType)) {
            return runMultimodalProcess(documentDO, kbDO, embeddingModel, mimeType);
        }

        // ======== 传统文本处理流程（保持不变）========
        ChunkingMode chunkingMode = ChunkingMode.fromValue(documentDO.getChunkStrategy());
        if (chunkingMode == null) {
            chunkingMode = ChunkingMode.STRUCTURE_AWARE;
            log.warn("文档分块策略未配置，使用默认策略: {}, docId={}", chunkingMode.getValue(), documentDO.getId());
        }
        ChunkingOptions config = buildChunkingOptions(chunkingMode, documentDO);

        long extractStart = System.currentTimeMillis();
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            // 三层递进 PDF 表格解析：
            //   ① 按解析引擎决策（MinerU / Tika，Tika 内部对含图表 PDF 走多模态）
            //   ② Tika → XHTML → Markdown（保留表格/标题/列表，但对 PDF 表格检测不可靠）
            //   ③ Tabula → 补充 Tika 遗漏的表格（在 TikaDocumentParser.extractAsMarkdown 内部完成）
            //   ④ LLM 视觉 → 当前面的步骤都不足 50 字符时，判定为扫描件，触发多模态提取
            String text = resolveParser(kbDO, documentDO).extractAsMarkdown(is, documentDO.getDocName());
            long extractDuration = System.currentTimeMillis() - extractStart;

            // 如果文档含嵌入图片（PDF/ODT/DOCX/PPTX），独立提取图片中的文字并追加
            if (documentVisionExtractor.mayContainEmbeddedImages(mimeType)) {
                String visionText = extractTextWithVisionWithTimeout(
                        documentDO.getFileUrl(), mimeType, documentDO.getDocName());
                if (StrUtil.isNotBlank(visionText)) {
                    log.info("视觉提取成功: 从嵌入图片中获取到 {} 字符", visionText.length());
                    text = text + "\n\n" + visionText;
                }
            }
            // 纯图片文件（扩展名如 png/jpg/gif 等），视觉提取是唯一文字来源
            if (isImageExtension(mimeType) && text.trim().isEmpty()) {
                log.info("图片文件, 触发视觉提取: mimeType={}, fileUrl={}", mimeType, documentDO.getFileUrl());
                String visionText = extractTextWithVisionWithTimeout(
                        documentDO.getFileUrl(), mimeType, documentDO.getDocName());
                if (StrUtil.isNotBlank(visionText)) {
                    log.info("图片视觉提取成功: 获取到 {} 字符", visionText.length());
                    text = visionText;
                }
            }
            // 文本型 PDF 提取文字过少（<50字符）时，判定为扫描件，触发视觉提取
            // 注意：此时 extractAsMarkdown() 已在内部尝试过 Tika + Tabula 提取，
            // 若 text 仍不足 50 字符，说明 PDF 是扫描件（无文字层），需要 LLM 视觉兜底
            if (documentVisionExtractor.isPdf(mimeType) && documentVisionExtractor.needsVisionExtraction(text)) {
                log.info("PDF文本提取不足，触发视觉提取: mimeType={}, fileUrl={}", mimeType, documentDO.getFileUrl());
                String visionText = extractTextWithVisionWithTimeout(
                        documentDO.getFileUrl(), mimeType, documentDO.getDocName());
                if (StrUtil.isNotBlank(visionText)) {
                    log.info("PDF视觉提取成功: 获取到 {} 字符", visionText.length());
                    text = visionText;
                }
            }

            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(chunkingMode);
            long chunkStart = System.currentTimeMillis();
            List<VectorChunk> chunks = chunkingStrategy.chunk(text, config);
            long chunkDuration = System.currentTimeMillis() - chunkStart;

            long embedStart = System.currentTimeMillis();
            // 必须携带 KB 配置维度：否则网关会按模型有效维度（t_ai_model.dimension 取 ≤2000 最大值）
            // 嵌入，导致入库维度与 KB 配置维度不一致，检索按 KB 维度查表永远为空
            chunkEmbeddingService.embed(chunks, embeddingModel, kbDO.getDimension());
            long embedDuration = System.currentTimeMillis() - embedStart;

            return new ChunkProcessResult(chunks, extractDuration, chunkDuration, embedDuration);
        } catch (Exception e) {
            throw new RuntimeException("文档内容提取或分块失败", e);
        }
    }

    private static boolean isMultimodalFileType(String fileType) {
        if (fileType == null) return false;
        String ft = fileType.toLowerCase();
        return ft.startsWith("image/")
                || "png".equals(ft) || "jpg".equals(ft) || "jpeg".equals(ft) || "gif".equals(ft)
                || "webp".equals(ft) || "bmp".equals(ft) || "tiff".equals(ft) || "tif".equals(ft)
                || "pdf".equals(ft) || "application/pdf".equalsIgnoreCase(ft)
                || "odt".equals(ft) || "ods".equals(ft) || "odp".equals(ft)
                || "docx".equals(ft) || "pptx".equals(ft) || "xlsx".equals(ft)
                || "doc".equals(ft) || "ppt".equals(ft) || "xls".equals(ft);
    }

    /**
     * 多模态 KB 统一处理入口：根据文件类型分派到不同的处理器
     */
    private ChunkProcessResult runMultimodalProcess(KnowledgeDocumentDO documentDO, KnowledgeBaseDO kbDO,
                                                     String embeddingModel, String mimeType) {
        log.info("多模态处理: fileType={}, docId={}, model={}", mimeType, documentDO.getId(), embeddingModel);

        if (isImageExtension(mimeType)) {
            return runImageProcess(documentDO, kbDO, embeddingModel, mimeType);
        }
        if (documentVisionExtractor.isPdf(mimeType)) {
            return runPdfMultimodalProcess(documentDO, kbDO, embeddingModel, mimeType);
        }
        return runOfficeMultimodalProcess(documentDO, kbDO, embeddingModel, mimeType);
    }

    private static String chunkBaseKey(String collectionName, String docId) {
        return RAGConstant.S3_DOCUMENT_PREFIX + "/" + collectionName + "/" + docId;
    }

    /**
     * 图片文件多模态处理：直接生成 IMAGE chunk，不走文本提取和分块
     */
    private ChunkProcessResult runImageProcess(KnowledgeDocumentDO documentDO, KnowledgeBaseDO kbDO,
                                                String embeddingModel, String mimeType) {
        String docId = documentDO.getId();
        String bucketName = RAGConstant.S3_BUCKET_NAME;
        String baseKey = chunkBaseKey(kbDO.getCollectionName(), docId);

        byte[] fileBytes;
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            fileBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取图片文件失败: " + docId, e);
        }

        VectorChunk chunk = imageChunkGenerator.generateFromImage(fileBytes, mimeType, bucketName, baseKey, docId, 0);
        if (chunk == null) {
            throw new RuntimeException("图片处理失败: " + docId);
        }
        List<VectorChunk> chunks = List.of(chunk);

        long embedStart = System.currentTimeMillis();
        chunkEmbeddingService.embed(chunks, embeddingModel, kbDO.getDimension());
        long embedDuration = System.currentTimeMillis() - embedStart;

        log.info("图片直接嵌入完成: docId={}, model={}", docId, embeddingModel);
        return new ChunkProcessResult(chunks, 0, 0, embedDuration);
    }

    /**
     * PDF 多模态处理：逐页检测内容类型，文字页 TEXT embed，图表页 IMAGE embed
     */
    private ChunkProcessResult runPdfMultimodalProcess(KnowledgeDocumentDO documentDO, KnowledgeBaseDO kbDO,
                                                        String embeddingModel, String mimeType) {
        String docId = documentDO.getId();
        String bucketName = RAGConstant.S3_BUCKET_NAME;
        String baseKey = chunkBaseKey(kbDO.getCollectionName(), docId);
        byte[] fileBytes;
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            fileBytes = is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取 PDF 文件失败: " + docId, e);
        }

        Set<Integer> imagePages = new HashSet<>(documentVisionExtractor.findPagesWithImages(fileBytes));
        log.info("PDF 页面检测: totalImagePages={}, docId={}", imagePages.size(), docId);

        List<VectorChunk> chunks = new ArrayList<>();
        int totalPages;
        try (PDDocument pdfDoc = Loader.loadPDF(fileBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdfDoc);
            PDFTextStripper stripper = new PDFTextStripper();
            totalPages = Math.min(pdfDoc.getNumberOfPages(), 50);

            for (int i = 0; i < totalPages; i++) {
                if (imagePages.contains(i)) {
                    VectorChunk chunk = imageChunkGenerator.generateSinglePage(pdfDoc, renderer, i,
                            bucketName, baseKey, docId);
                    if (chunk != null) chunks.add(chunk);
                } else {
                    stripper.setStartPage(i + 1);
                    stripper.setEndPage(i + 1);
                    String pageText = stripper.getText(pdfDoc).trim();
                    if (StrUtil.isNotBlank(pageText)) {
                        chunks.add(VectorChunk.builder()
                                .chunkId(IdUtil.getSnowflakeNextIdStr())
                                .index(i)
                                .content(pageText)
                                .contentType("TEXT")
                                .metadata(Map.of("page_number", i, "doc_id", docId))
                                .build());
                    }
                }
            }

            if (totalPages > 0 && chunks.isEmpty()) {
                throw new RuntimeException("PDF 多模态处理未产生任何分块: docId=" + docId);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("PDF 多模态处理失败: docId=" + docId, e);
        }

        long embedStart = System.currentTimeMillis();
        chunkEmbeddingService.embed(chunks, embeddingModel, kbDO.getDimension());
        long embedDuration = System.currentTimeMillis() - embedStart;

        log.info("PDF 多模态处理完成: docId={}, pages={}, textChunks={}, imageChunks={}, model={}",
                docId, totalPages,
                chunks.stream().filter(c -> !c.isImage()).count(),
                chunks.stream().filter(VectorChunk::isImage).count(),
                embeddingModel);
        return new ChunkProcessResult(chunks, 0, 0, embedDuration);
    }

    /**
     * Office 文档多模态处理：文本 TEXT embed + 嵌入图片 IMAGE embed
     */
    private ChunkProcessResult runOfficeMultimodalProcess(KnowledgeDocumentDO documentDO, KnowledgeBaseDO kbDO,
                                                           String embeddingModel, String mimeType) {
        String docId = documentDO.getId();
        long extractStart = System.currentTimeMillis();

        // 先用解析引擎决策的解析器提取全文
        try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
            String text = resolveParser(kbDO, documentDO).extractAsMarkdown(is, documentDO.getDocName());
            long extractDuration = System.currentTimeMillis() - extractStart;

            // 简单文本分块（Office 文档通常结构清晰，用结构感知策略）
            ChunkingStrategy chunkingStrategy = chunkingStrategyFactory.requireStrategy(ChunkingMode.STRUCTURE_AWARE);
            ChunkingOptions config = ChunkingMode.STRUCTURE_AWARE.createDefaultOptions(1400, 0);
            List<VectorChunk> textChunks = chunkingStrategy.chunk(text, config);
            textChunks.forEach(c -> c.setContentType("TEXT"));

            // 尝试提取嵌入图片
            List<VectorChunk> imageChunks = List.of();
            if (documentVisionExtractor.mayContainEmbeddedImages(mimeType)) {
                imageChunks = extractEmbeddedImageChunks(documentDO, kbDO, mimeType);
                if (!imageChunks.isEmpty()) {
                    log.info("提取到 {} 个嵌入图片块: docId={}", imageChunks.size(), docId);
                }
            }

            List<VectorChunk> allChunks = new ArrayList<>(textChunks);
            allChunks.addAll(imageChunks);

            long embedStart = System.currentTimeMillis();
            chunkEmbeddingService.embed(allChunks, embeddingModel, kbDO.getDimension());
            long embedDuration = System.currentTimeMillis() - embedStart;

            log.info("Office 文档多模态处理完成: docId={}, textChunks={}, imageChunks={}, model={}",
                    docId, textChunks.size(), imageChunks.size(), embeddingModel);
            return new ChunkProcessResult(allChunks, extractDuration, 0, embedDuration);
        } catch (Exception e) {
            throw new RuntimeException("Office 文档多模态处理失败: docId=" + docId, e);
        }
    }

    /**
     * 从 Office 文档中提取嵌入图片并生成 IMAGE chunk
     */
    private List<VectorChunk> extractEmbeddedImageChunks(KnowledgeDocumentDO documentDO, KnowledgeBaseDO kbDO,
                                                          String mimeType) {
        String docId = documentDO.getId();
        String bucketName = RAGConstant.S3_BUCKET_NAME;
        String baseKey = chunkBaseKey(kbDO.getCollectionName(), docId);
        try {
            byte[] fileBytes;
            try (InputStream is = fileStorageService.openStream(documentDO.getFileUrl())) {
                fileBytes = is.readAllBytes();
            }
            List<DocumentVisionExtractor.ExtractedImage> images =
                    documentVisionExtractor.extractEmbeddedImageBytes(fileBytes);
            if (images.isEmpty()) return List.of();

            List<VectorChunk> chunks = new ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                DocumentVisionExtractor.ExtractedImage img = images.get(i);
                VectorChunk chunk = imageChunkGenerator.generateFromImage(
                        img.bytes(), img.mimeType(), bucketName, baseKey, docId, i);
                if (chunk != null) chunks.add(chunk);
            }
            return chunks;
        } catch (Exception e) {
            log.warn("提取嵌入图片失败: docId={}", docId, e);
            return List.of();
        }
    }

    // 判断文件扩展名是否为常见图片格式
    private static boolean isImageExtension(String fileType) {
        if (fileType == null) return false;
        return switch (fileType.toLowerCase()) {
            case "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "svg", "ico" -> true;
            default -> fileType.startsWith("image/");
        };
    }

    // 根据 S3 URL 的文件扩展名推断 MIME 类型
    private static String detectImageMimeType(String url) {
        if (url == null) return "image/jpeg";
        String lower = url.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    // 带超时（120秒）的视觉提取，防止 LLM 调用挂死线程
    private static final ExecutorService VISION_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "vision-extract-");
        t.setDaemon(true);
        return t;
    });

    private String extractTextWithVisionWithTimeout(String fileUrl, String mimeType, String fileName) {
        try {
            Future<String> future = VISION_EXECUTOR.submit(() ->
                    documentVisionExtractor.extractTextWithVision(fileUrl, mimeType, fileName));
            return future.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("视觉提取超时（120s），跳过视觉提取: fileUrl={}", fileUrl);
            return "";
        } catch (Exception e) {
            log.warn("视觉提取异常: fileUrl={}", fileUrl, e);
            return "";
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
                .supportsImageEmbedding(kbDO.getSupportsImageEmbedding() != null && kbDO.getSupportsImageEmbedding() == 1)
                .build();

        IngestionContext result;
        try {
            result = ingestionEngine.execute(pipelineDef, context);
        } catch (Exception e) {
            log.error("Pipeline执行异常：docId={}", docId, e);
            task.setStatus(IngestionStatus.FAILED.getValue());
            task.setErrorMessage(e.getMessage());
            task.setCompletedAt(new Date());
            ingestionTaskMapper.updateById(task);
            throw new RuntimeException("Pipeline执行异常：docId=" + docId, e);
        }

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

    // 将文档状态标记为分块失败（FAILED）
    // 不使用 transactionOperations 包裹，避免事务管理器引发二次异常导致状态卡死
    private void markChunkFailed(String docId) {
        try {
            KnowledgeDocumentDO update = new KnowledgeDocumentDO();
            update.setId(docId);
            update.setStatus(DocumentStatus.FAILED.getCode());
            update.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(update);
        } catch (Exception e) {
            log.error("标记文档分块失败状态异常，docId={}", docId, e);
        }
    }

    /**
     * 删除文档
     * <p>
     * 处理流程：
     * 1. 校验文档存在且未在分块中
      * 2. 删除关联的分块、调度、日志记录
      * 3. 物理删除文档
      * 4. 清理向量库数据和存储文件
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在", KnowledgeErrorCode.DOCUMENT_NOT_FOUND));

        // 禁止在文档分块运行时删除
        if (DocumentStatus.RUNNING.getCode().equals(documentDO.getStatus())) {
            throw new ClientException("文档正在分块中，无法删除");
        }

        knowledgeChunkService.deleteByDocId(docId);
        scheduleService.deleteByDocId(docId);
        chunkLogMapper.delete(Wrappers.lambdaQuery(KnowledgeDocumentChunkLogDO.class)
                .eq(KnowledgeDocumentChunkLogDO::getDocId, docId));

        documentMapper.deleteById(documentDO);

        String collectionName = resolveCollectionName(documentDO.getKbId());
        int dimension = resolveDimension(documentDO.getKbId());
        vectorStoreService.deleteDocumentVectors(collectionName, docId, dimension);
        deleteStoredFileQuietly(documentDO);

        // 清理图谱数据（关系 + 抽取缓存 + 孤立实体），图谱总开关关闭时静默跳过
        graphExtractionService.deleteDocumentGraph(documentDO.getKbId(), docId);
    }

    @Override
    public KnowledgeDocumentVO get(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在", KnowledgeErrorCode.DOCUMENT_NOT_FOUND));
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
        Assert.notNull(documentDO, () -> new ClientException("文档不存在", KnowledgeErrorCode.DOCUMENT_NOT_FOUND));

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
                if (chunkingMode == null) {
                    chunkingMode = ChunkingMode.STRUCTURE_AWARE;
                }
                String chunkConfig = validateAndNormalizeChunkConfig(chunkingMode, requestParam.getChunkConfig());
                updateWrapper.set(KnowledgeDocumentDO::getChunkStrategy, chunkingMode.getValue());
                updateWrapper.set(KnowledgeDocumentDO::getChunkConfig, chunkConfig);
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

        // 文档级解析引擎覆盖（可空：传值则覆盖，否则沿用知识库级）
        if (requestParam.getParseEngine() != null) {
            String normalized = StrUtil.isBlank(requestParam.getParseEngine())
                    ? null : ParseEngine.normalize(requestParam.getParseEngine()).getValue();
            updateWrapper.set(KnowledgeDocumentDO::getParseEngine, normalized);
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
        Assert.notNull(documentDO, () -> new ClientException("文档不存在", KnowledgeErrorCode.DOCUMENT_NOT_FOUND));

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
            vectorChunks = chunks.stream().map(each -> {
                        String contentType = each.getContentType();
                        boolean isImage = "IMAGE".equalsIgnoreCase(contentType);
                        Map<String, Object> metadata = new HashMap<>();

                        VectorChunk.VectorChunkBuilder builder = VectorChunk.builder()
                                .chunkId(each.getId())
                                .content(each.getContent())
                                .index(each.getChunkIndex())
                                .contentType(contentType);

                        if (isImage && each.getImageUrl() != null && !each.getImageUrl().isBlank()) {
                            metadata.put("image_url", each.getImageUrl());
                            try (InputStream is = fileStorageService.openStream(each.getImageUrl())) {
                                byte[] imageBytes = is.readAllBytes();
                                String mimeType = detectImageMimeType(each.getImageUrl());
                                String base64 = Base64.getEncoder().encodeToString(imageBytes);
                                metadata.put("image_base64", "data:" + mimeType + ";base64," + base64);
                            } catch (Exception e) {
                                log.warn("enable() 下载图片失败，回退为 TEXT 类型: chunkId={}, url={}",
                                        each.getId(), each.getImageUrl(), e);
                                builder.contentType("TEXT");
                                metadata.clear();
                            }
                        }

                        return builder.metadata(metadata).build();
                    })
                    .toList();
            if (CollUtil.isEmpty(vectorChunks)) {
                // 无分块时若静默返回，文档将永远无法启用且用户无从感知；
                // 直接抛错，提示先执行分块
                throw new ServiceException("文档没有可用的分块，无法启用，请先执行分块: docId=" + docId);
            }
            chunkEmbeddingService.embed(vectorChunks, kbDO.getEmbeddingModel(), kbDO.getDimension());
        }

        final List<VectorChunk> finalVectorChunks = vectorChunks;
        transactionOperations.executeWithoutResult(status -> {
            documentDO.setEnabled(targetEnabled);
            documentDO.setUpdatedBy(UserContext.getUsername());
            documentMapper.updateById(documentDO);
            scheduleService.syncScheduleIfExists(documentDO);
            knowledgeChunkService.updateEnabledByDocId(docId, String.valueOf(kbDO.getId()), enabled);

            if (!enabled) {
                vectorStoreService.deleteDocumentVectors(collectionName, docId, kbDO.getDimension());
            } else {
                vectorStoreService.indexDocumentChunks(collectionName, docId, kbDO.getDimension(), finalVectorChunks);
            }
        });

        // 图谱联动：禁用 → 清理文档图数据；启用 → 增量重建（复用抽取缓存，零额外 LLM 成本）
        if (enabled) {
            graphExtractionService.extractForDocumentAsync(kbDO.getId(), docId, finalVectorChunks);
        } else {
            graphExtractionService.deleteDocumentGraph(kbDO.getId(), docId);
        }
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
            throw new ClientException("知识库不存在: " + kbId, KnowledgeErrorCode.KB_NOT_FOUND);
        }
        return kbDO.getCollectionName();
    }

    // 根据知识库 ID 查询向量维度
    private int resolveDimension(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null) {
            throw new ClientException("知识库不存在: " + kbId, KnowledgeErrorCode.KB_NOT_FOUND);
        }
        if (kbDO.getDimension() != null && kbDO.getDimension() > 0) {
            return kbDO.getDimension();
        }
        log.warn("知识库 {} 未配置向量维度，使用默认值 1536。请检查嵌入模型配置。", kbId);
        return 1536;
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
            if (chunkingMode == null) {
                chunkingMode = ChunkingMode.STRUCTURE_AWARE;
            }
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
            Object value = config.get(key);
            if (value == null) {
                throw new ClientException("分块参数不能为空: " + key);
            }
            int num;
            if (value instanceof Number n) {
                num = n.intValue();
            } else if (value instanceof String s && s.matches("-?\\d+")) {
                num = Integer.parseInt(s.trim());
            } else {
                throw new ClientException("分块参数必须为整数: " + key);
            }
            // 大小类参数必须 >= 1（<=0 会导致切分死循环），重叠类参数必须 >= 0
            boolean overlapParam = key.endsWith("overlapSize") || key.endsWith("overlapChars");
            if (overlapParam) {
                if (num < 0) {
                    throw new ClientException("分块参数 " + key + " 不能为负数");
                }
            } else if (num < 1) {
                throw new ClientException("分块参数 " + key + " 必须大于等于 1");
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
