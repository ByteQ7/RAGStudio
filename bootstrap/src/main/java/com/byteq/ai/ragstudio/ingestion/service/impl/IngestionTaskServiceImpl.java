package com.byteq.ai.ragstudio.ingestion.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.rag.controller.request.DocumentSourceRequest;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionTaskCreateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionTaskNodeVO;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionTaskVO;
import com.byteq.ai.ragstudio.ingestion.dao.entity.IngestionTaskDO;
import com.byteq.ai.ragstudio.ingestion.dao.entity.IngestionTaskNodeDO;
import com.byteq.ai.ragstudio.ingestion.dao.mapper.IngestionTaskMapper;
import com.byteq.ai.ragstudio.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.ingestion.domain.context.DocumentSource;
import com.byteq.ai.ragstudio.ingestion.domain.context.IngestionContext;
import com.byteq.ai.ragstudio.ingestion.domain.context.NodeLog;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionNodeType;
import com.byteq.ai.ragstudio.ingestion.domain.enums.IngestionStatus;
import com.byteq.ai.ragstudio.ingestion.domain.enums.SourceType;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.NodeConfig;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition;
import com.byteq.ai.ragstudio.ingestion.domain.result.IngestionResult;
import com.byteq.ai.ragstudio.ingestion.engine.IngestionEngine;
import com.byteq.ai.ragstudio.ingestion.util.MimeTypeDetector;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeChunkService;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.byteq.ai.ragstudio.rag.core.vector.VectorSpaceId;
import com.byteq.ai.ragstudio.ingestion.service.IngestionPipelineService;
import com.byteq.ai.ragstudio.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据摄入任务服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionTaskServiceImpl implements IngestionTaskService {

    private final IngestionEngine engine;
    private final IngestionPipelineService pipelineService;
    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskNodeMapper taskNodeMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkService knowledgeChunkService;
    private final ObjectMapper objectMapper;

    /**
     * 执行数据摄入任务
     * <p>
     * 根据请求中的流水线 ID 和文档来源配置，创建并执行一个摄入任务。
     * 将请求中的文档来源转换为内部领域对象后委托给内部执行方法。
     * </p>
     *
     * @param request 创建任务请求，包含流水线 ID 和文档来源配置
     * @return 摄入任务执行结果
     */
    @Override
    public IngestionResult execute(IngestionTaskCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        DocumentSource source = toSource(request.getSource());
        return executeInternal(request.getPipelineId(), source, null, null, request.getVectorSpaceId());
    }

    /**
     * 上传文件并执行摄入任务
     * <p>
     * 流程:
     * 1. 读取上传文件的字节内容
     * 2. 自动检测文件的 MIME 类型
     * 3. 构建本地文件类型的文档来源
     * 4. 委托内部方法执行流水线处理
     * </p>
     *
     * @param pipelineId 流水线 ID
     * @param file       上传的文件
     * @return 摄入任务执行结果
     */
    @Override
    public IngestionResult upload(String pipelineId, MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("文件不能为空"));
        try {
            byte[] bytes = file.getBytes();
            String fileName = file.getOriginalFilename();
            if (!StringUtils.hasText(fileName)) {
                fileName = "upload.bin";
            }
            String mimeType = MimeTypeDetector.detect(bytes, fileName);
            DocumentSource source = DocumentSource.builder()
                    .type(SourceType.FILE)
                    .location(fileName)
                    .fileName(fileName)
                    .build();
            return executeInternal(pipelineId, source, bytes, mimeType, null);
        } catch (Exception e) {
            throw new ClientException("读取上传文件失败: " + e.getMessage());
        }
    }

    /**
     * 根据任务 ID 查询任务详情，包括状态、来源、日志和元数据
     *
     * @param taskId 任务 ID
     * @return 任务详情视图对象
     */
    @Override
    public IngestionTaskVO get(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("未找到任务"));
        return toVO(task);
    }

    /**
     * 分页查询摄入任务列表，支持按状态筛选，按创建时间倒序排列
     *
     * @param page   分页参数
     * @param status 任务状态筛选条件（可选）
     * @return 任务分页结果
     */
    @Override
    public IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status) {
        Page<IngestionTaskDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        String normalizedStatus = normalizeStatus(status);
        LambdaQueryWrapper<IngestionTaskDO> qw = new LambdaQueryWrapper<IngestionTaskDO>()
                .eq(IngestionTaskDO::getDeleted, 0)
                .eq(StringUtils.hasText(normalizedStatus), IngestionTaskDO::getStatus, normalizedStatus)
                .orderByDesc(IngestionTaskDO::getCreateTime);
        IPage<IngestionTaskDO> result = taskMapper.selectPage(mpPage, qw);
        Page<IngestionTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    /**
     * 查询任务各节点的执行记录，按节点顺序和 ID 升序排列
     *
     * @param taskId 任务 ID
     * @return 节点执行记录列表
     */
    @Override
    public List<IngestionTaskNodeVO> listNodes(String taskId) {
        LambdaQueryWrapper<IngestionTaskNodeDO> qw = new LambdaQueryWrapper<IngestionTaskNodeDO>()
                .eq(IngestionTaskNodeDO::getDeleted, 0)
                .eq(IngestionTaskNodeDO::getTaskId, taskId)
                .orderByAsc(IngestionTaskNodeDO::getNodeOrder)
                .orderByAsc(IngestionTaskNodeDO::getId);
        List<IngestionTaskNodeDO> nodes = taskNodeMapper.selectList(qw);
        return nodes.stream().map(this::toNodeVO).toList();
    }

    // 任务执行核心流程:
    // 1. 解析流水线定义
    // 2. 创建任务记录并持久化
    // 3. 构建执行上下文
    // 4. 调用引擎执行流水线
    // 5. 保存节点日志和更新任务状态
    // 6. 创建知识库文档记录
    private IngestionResult executeInternal(String pipelineId,
                                            DocumentSource source,
                                            byte[] rawBytes,
                                            String mimeType,
                                            VectorSpaceId vectorSpaceId) {
        String resolvedPipelineId = resolvePipelineId(pipelineId);
        PipelineDefinition pipeline = pipelineService.getDefinition(resolvedPipelineId);

        IngestionTaskDO task = IngestionTaskDO.builder()
                .pipelineId(resolvedPipelineId)
                .sourceType(source.getType() == null ? null : source.getType().getValue())
                .sourceLocation(source.getLocation())
                .sourceFileName(source.getFileName())
                .status(IngestionStatus.RUNNING.getValue())
                .chunkCount(0)
                .startedAt(new Date())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        taskMapper.insert(task);

        IngestionContext context = IngestionContext.builder()
                .taskId(String.valueOf(task.getId()))
                .pipelineId(resolvedPipelineId)
                .source(source)
                .rawBytes(rawBytes)
                .mimeType(mimeType)
                .vectorSpaceId(vectorSpaceId)
                .logs(new ArrayList<>())
                .build();

        IngestionContext result = engine.execute(pipeline, context);
        saveNodeLogs(task, pipeline, result.getLogs());
        updateTaskFromContext(task, result);

        // 引擎执行成功后，创建知识库文档和分块记录
        if (result.getStatus() == IngestionStatus.COMPLETED
                && result.getChunks() != null && !result.getChunks().isEmpty()) {
            createKnowledgeDocument(task, pipeline, result, source);
        }

        return IngestionResult.builder()
                .taskId(result.getTaskId())
                .pipelineId(result.getPipelineId())
                .status(result.getStatus())
                .chunkCount(result.getChunks() == null ? 0 : result.getChunks().size())
                .message(result.getError() == null ? "OK" : result.getError().getMessage())
                .build();
    }

    // 根据引擎执行结果更新任务状态、分块数、错误信息、日志和元数据
    private void updateTaskFromContext(IngestionTaskDO task, IngestionContext context) {
        task.setStatus(context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue());
        task.setChunkCount(context.getChunks() == null ? 0 : context.getChunks().size());
        task.setErrorMessage(context.getError() == null ? null : context.getError().getMessage());
        task.setCompletedAt(new Date());
        task.setUpdatedBy(UserContext.getUsername());
        task.setLogsJson(writeJson(buildLogSummary(context.getLogs())));
        task.setMetadataJson(writeJson(buildTaskMetadata(context)));
        taskMapper.updateById(task);
    }

    /**
     * 创建知识库文档记录及分块记录
     * <p>
     * 流水线任务执行完成后，在知识库中创建对应的文档记录和分块数据，
     * 使处理结果在知识库文档列表中可见。
     * </p>
     * <p>
     * 注意：独立任务入口已从前端移除，文档统一通过知识库管理上传并选择数据通道处理。
     * 此方法仅作为 API 兼容保留，新流水线不再包含 collectionName 配置。
     * </p>
     */
    private void createKnowledgeDocument(IngestionTaskDO task, PipelineDefinition pipeline,
                                         IngestionContext context, DocumentSource source) {
        // 从上下文向量空间 ID 获取集合名称（由知识库触发时设置）
        String collectionName = null;
        if (context.getVectorSpaceId() != null && StringUtils.hasText(context.getVectorSpaceId().getLogicalName())) {
            collectionName = context.getVectorSpaceId().getLogicalName();
        }
        // 兼容旧流水线：尝试从 IndexerNode 配置中读取
        if (!StringUtils.hasText(collectionName)) {
            collectionName = extractIndexerCollectionName(pipeline);
        }
        if (!StringUtils.hasText(collectionName)) {
            log.info("独立任务未关联知识库集合，跳过创建知识库文档（流水线任务 ID={}）", task.getId());
            return;
        }

        // 2. 根据 collectionName 查找知识库
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getCollectionName, collectionName)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (kb == null) {
            log.warn("未找到 collectionName 为 {} 的知识库，跳过创建知识库文档", collectionName);
            return;
        }

        // 3. 创建文档记录
        String docId = IdUtil.getSnowflakeNextIdStr();
        String fileName = source != null ? source.getFileName() : task.getSourceFileName();
        String sourceLocation = source != null ? source.getLocation() : task.getSourceLocation();
        String sourceType = source != null && source.getType() != null
                ? source.getType().getValue() : task.getSourceType();

        // 从 mimeType 推断文件类型
        String fileType = context.getMimeType() != null ? context.getMimeType() : (fileName != null ? MimeTypeDetector.detect(context.getRawBytes(), fileName) : null); // 使用 rawBytes 需要确保不为 null
        // 简化处理：根据文件后缀推断
        String simpleFileType = "other";
        if (fileType != null) {
            if (fileType.contains("pdf")) simpleFileType = "pdf";
            else if (fileType.contains("markdown") || fileType.contains("text/x-markdown")) simpleFileType = "markdown";
            else if (fileType.contains("word") || fileType.contains("msword")) simpleFileType = "docx";
            else if (fileType.contains("excel") || fileType.contains("spreadsheet")) simpleFileType = "xlsx";
            else if (fileType.contains("text")) simpleFileType = "txt";
            else if (fileType.contains("image")) simpleFileType = "image";
        }
        // 或者从 fileName 后缀推断
        if ("other".equals(simpleFileType) && fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".pdf")) simpleFileType = "pdf";
            else if (lower.endsWith(".md") || lower.endsWith(".markdown")) simpleFileType = "markdown";
            else if (lower.endsWith(".doc") || lower.endsWith(".docx")) simpleFileType = "docx";
            else if (lower.endsWith(".txt")) simpleFileType = "txt";
        }

        KnowledgeDocumentDO documentDO = KnowledgeDocumentDO.builder()
                .id(docId)
                .kbId(String.valueOf(kb.getId()))
                .docName(fileName != null ? fileName : "未命名文档")
                .sourceType(sourceType)
                .sourceLocation(sourceLocation)
                .enabled(1)
                .chunkCount(context.getChunks().size())
                .fileUrl(sourceLocation != null ? sourceLocation : "")
                .fileType(simpleFileType)
                .processMode("pipeline")
                .pipelineId(task.getPipelineId())
                .status("success")
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        knowledgeDocumentMapper.insert(documentDO);

        // 4. 创建分块记录
        List<KnowledgeChunkCreateRequest> chunkRequests = context.getChunks().stream()
                .map(vc -> {
                    KnowledgeChunkCreateRequest req = new KnowledgeChunkCreateRequest();
                    req.setChunkId(vc.getChunkId());
                    req.setIndex(vc.getIndex());
                    req.setContent(vc.getContent());
                    return req;
                })
                .toList();
        knowledgeChunkService.batchCreate(docId, chunkRequests);

        log.info("流水线任务完成，已创建知识库文档 docId={}, kbName={}, chunkCount={}",
                docId, kb.getName(), chunkRequests.size());
    }

    /**
     * 从流水线定义中提取 IndexerNode 的 collectionName 配置
     */
    private String extractIndexerCollectionName(PipelineDefinition pipeline) {
        if (pipeline == null || pipeline.getNodes() == null) {
            return null;
        }
        return pipeline.getNodes().stream()
                .filter(n -> n != null && "indexer".equals(n.getNodeType()))
                .findFirst()
                .map(n -> {
                    if (n.getSettings() != null && !n.getSettings().isNull()) {
                        // 使用path()方法安全获取，如果不存在会返回MissingNode而不是null
                        return n.getSettings().path("collectionName").asText(null);
                    }
                    return null;
                })
                .orElse(null);
    }

    // 将引擎产生的节点执行日志逐一持久化到任务节点表
    private void saveNodeLogs(IngestionTaskDO task, PipelineDefinition pipeline, List<NodeLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        Map<String, Integer> nodeOrderMap = buildNodeOrderMap(pipeline);
        for (NodeLog log : logs) {
            String status = resolveNodeStatus(log);
            String outputJson = truncateOutputJson(log.getOutput());
            IngestionTaskNodeDO nodeDO = IngestionTaskNodeDO.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .nodeId(log.getNodeId())
                    .nodeType(log.getNodeType())
                    .nodeOrder(nodeOrderMap.getOrDefault(log.getNodeId(), 0))
                    .status(status)
                    .durationMs(log.getDurationMs())
                    .message(log.getMessage())
                    .errorMessage(log.getError())
                    .outputJson(outputJson)
                    .build();
            taskNodeMapper.insert(nodeDO);
        }
    }

    // 根据流水线的节点链构建节点执行顺序映射，用于日志排序
    private Map<String, Integer> buildNodeOrderMap(PipelineDefinition pipeline) {
        Map<String, Integer> orderMap = new HashMap<>();
        if (pipeline == null || pipeline.getNodes() == null || pipeline.getNodes().isEmpty()) {
            return orderMap;
        }
        Map<String, NodeConfig> nodeMap = new LinkedHashMap<>();
        for (NodeConfig node : pipeline.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                continue;
            }
            nodeMap.putIfAbsent(node.getNodeId(), node);
        }
        if (nodeMap.isEmpty()) {
            return orderMap;
        }
        Set<String> referenced = new HashSet<>();
        for (NodeConfig node : nodeMap.values()) {
            if (StringUtils.hasText(node.getNextNodeId())) {
                referenced.add(node.getNextNodeId());
            }
        }
        int order = 1;
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeMap.keySet()) {
            if (referenced.contains(nodeId)) {
                continue;
            }
            String current = nodeId;
            while (StringUtils.hasText(current) && !visited.contains(current)) {
                orderMap.put(current, order++);
                visited.add(current);
                NodeConfig config = nodeMap.get(current);
                if (config == null) {
                    break;
                }
                current = config.getNextNodeId();
            }
        }
        for (String nodeId : nodeMap.keySet()) {
            if (!visited.contains(nodeId)) {
                orderMap.put(nodeId, order++);
            }
        }
        return orderMap;
    }

    // 根据节点日志判断节点执行状态：success/failed/skipped
    private String resolveNodeStatus(NodeLog log) {
        if (log == null) {
            return "failed";
        }
        if (!log.isSuccess()) {
            return "failed";
        }
        String message = log.getMessage();
        if (message != null && message.startsWith("Skipped:")) {
            return "skipped";
        }
        return "success";
    }

    // 从上下文中汇总任务的元数据（包括关键词和问题）
    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    // 校验流水线 ID 是否有效，为空时抛出异常
    private String resolvePipelineId(String pipelineId) {
        if (StringUtils.hasText(pipelineId)) {
            return pipelineId;
        }
        throw new ClientException("必须传流水线ID");
    }

    // 标准化任务状态字符串，通过枚举校验并返回规范值
    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        try {
            return IngestionStatus.fromValue(status).getValue();
        } catch (IllegalArgumentException ex) {
            return status;
        }
    }

    // 将文档来源请求对象转换为内部 DocumentSource 领域对象
    private DocumentSource toSource(DocumentSourceRequest request) {
        Assert.notNull(request, () -> new ClientException("文档来源不能为空"));
        DocumentSource source = DocumentSource.builder()
                .type(request.getType())
                .location(request.getLocation())
                .fileName(request.getFileName())
                .credentials(request.getCredentials())
                .build();
        if (source.getType() == null) {
            throw new ClientException("文档来源类型不能为空");
        }
        return source;
    }

    // 将任务 DO 转换为 VO 视图对象，包括状态标准化和 JSON 字段反序列化
    private IngestionTaskVO toVO(IngestionTaskDO task) {
        return IngestionTaskVO.builder()
                .id(String.valueOf(task.getId()))
                .pipelineId(String.valueOf(task.getPipelineId()))
                .sourceType(normalizeSourceType(task.getSourceType()))
                .sourceLocation(task.getSourceLocation())
                .sourceFileName(task.getSourceFileName())
                .status(normalizeStatus(task.getStatus()))
                .chunkCount(task.getChunkCount())
                .errorMessage(task.getErrorMessage())
                .logs(readLogs(task.getLogsJson()))
                .metadata(parseMetadata(task.getMetadataJson()))
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdBy(task.getCreatedBy())
                .createTime(task.getCreateTime())
                .updateTime(task.getUpdateTime())
                .build();
    }

    // 将任务节点 DO 转换为 VO 视图对象
    private IngestionTaskNodeVO toNodeVO(IngestionTaskNodeDO node) {
        return IngestionTaskNodeVO.builder()
                .id(String.valueOf(node.getId()))
                .taskId(String.valueOf(node.getTaskId()))
                .pipelineId(String.valueOf(node.getPipelineId()))
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .nodeOrder(node.getNodeOrder())
                .status(normalizeNodeStatus(node.getStatus()))
                .durationMs(node.getDurationMs())
                .message(node.getMessage())
                .errorMessage(node.getErrorMessage())
                .output(parseOutput(node.getOutputJson()))
                .createTime(node.getCreateTime())
                .updateTime(node.getUpdateTime())
                .build();
    }

    // 将对象序列化为 JSON 字符串，序列化失败返回 null
    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
    }

    // 构建精简版日志摘要，去除 output 字段以减小存储体积
    private List<NodeLog> buildLogSummary(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream()
                .map(log -> NodeLog.builder()
                        .nodeId(log.getNodeId())
                        .nodeType(log.getNodeType())
                        .message(log.getMessage())
                        .durationMs(log.getDurationMs())
                        .success(log.isSuccess())
                        .error(log.getError())
                        .output(null)
                        .build())
                .toList();
    }

    // 从 JSON 字符串反序列化为节点日志列表，解析失败返回空列表
    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    // 从 JSON 字符串反序列化为元数据 Map
    private Map<String, Object> parseMetadata(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("元数据 JSON 解析失败: {}", raw, e);
            return Map.of();
        }
    }

    // 从 JSON 字符串反序列化为节点输出 Map
    private Map<String, Object> parseOutput(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("输出 JSON 解析失败: {}", raw, e);
            return Map.of();
        }
    }

    // 标准化文档来源类型字符串
    private String normalizeSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return sourceType;
        }
        try {
            return SourceType.fromValue(sourceType).getValue();
        } catch (IllegalArgumentException ex) {
            return sourceType;
        }
    }

    // 标准化节点类型字符串
    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }

    // 标准化节点执行状态字符串，将连字符替换为下划线
    private String normalizeNodeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return status;
        }
        String trimmed = status.trim();
        String lower = trimmed.toLowerCase();
        return lower.replace('-', '_');
    }

    /**
     * 截断过大的输出 JSON，防止超过数据库的 max_allowed_packet 限制
     * 默认限制为 1MB，截断后仍保持有效 JSON 格式
     */
    private String truncateOutputJson(Object output) {
        if (output == null) {
            return null;
        }
        String json = writeJson(output);
        if (json == null) {
            return null;
        }
        // 限制为 1MB (1,048,576 字节)，留有余量避免接近 4MB 上限
        int maxSize = 1024 * 1024;
        if (json.length() <= maxSize) {
            return json;
        }
        // 截断后生成有效的 JSON 对象，包含截断标记和原始大小
        // 预留空间给包裹结构：{"_truncated":true,"_originalSize":N,"_preview":"..."}
        int previewMaxLen = maxSize - 120;
        String preview = json.substring(0, Math.max(0, previewMaxLen));
        // 确保 preview 不以未闭合的转义字符结尾
        int backslashCount = 0;
        for (int i = preview.length() - 1; i >= 0 && preview.charAt(i) == '\\'; i--) {
            backslashCount++;
        }
        if (backslashCount % 2 != 0) {
            preview = preview.substring(0, preview.length() - 1);
        }
        return "{\"_truncated\":true,\"_originalSize\":" + json.length()
                + ",\"_preview\":\"" + escapeJsonString(preview) + "\"}";
    }

    // 对字符串进行 JSON 转义处理，处理引号、反斜杠、换行符和控制字符
    private String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
