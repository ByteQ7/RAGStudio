package com.byteq.ai.ragstudio.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiModelDO;
import com.byteq.ai.ragstudio.aimodel.dao.entity.AiProviderDO;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiModelMapper;
import com.byteq.ai.ragstudio.aimodel.dao.mapper.AiProviderMapper;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBasePageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeBaseVO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeBaseDO;
import com.byteq.ai.ragstudio.knowledge.dao.entity.KnowledgeDocumentDO;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.byteq.ai.ragstudio.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.byteq.ai.ragstudio.framework.context.UserContext;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.framework.exception.ServiceException;
import com.byteq.ai.ragstudio.rag.core.vector.VectorSpaceId;
import com.byteq.ai.ragstudio.rag.core.vector.VectorSpaceSpec;
import com.byteq.ai.ragstudio.rag.core.vector.VectorStoreAdmin;
import com.byteq.ai.ragstudio.infra.embedding.EmbeddingService;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final S3Client s3Client;
    private final AiModelMapper aiModelMapper;
    private final AiProviderMapper aiProviderMapper;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;

    /**
     * 创建知识库
     * <p>
     * 处理流程：
     * 1. 名称重复校验
     * 2. 创建 S3 存储桶
     * 3. 插入数据库记录并初始化向量空间
     * 4. 若 DB 或向量空间创建失败，补偿删除 S3 桶
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public String create(KnowledgeBaseCreateRequest requestParam) {
        // 名称重复校验
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, name)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        // 校验并解析向量维度
        int dimension = validateAndResolveDimension(requestParam.getDimension(), requestParam.getEmbeddingModel());

        // 若未传入 embeddingProvider，从模型配置自动推导
        String provider = requestParam.getEmbeddingProvider();
        if (!StringUtils.hasText(provider) && StringUtils.hasText(requestParam.getEmbeddingModel())) {
            provider = resolveEmbeddingProvider(requestParam.getEmbeddingModel());
        }

        // 发送真实向量化探测，校验 Embedding 模型可用
        if (StringUtils.hasText(requestParam.getEmbeddingModel())) {
            try {
                embeddingService.embedDirect("你好", requestParam.getEmbeddingModel());
            } catch (Exception e) {
                log.warn("Embedding 模型探测失败，拒绝创建知识库: modelId={}", requestParam.getEmbeddingModel(), e);
                String rootCause = e.getMessage() != null ? e.getMessage() : "未知错误";
                throw new ServiceException("Embedding 模型 \"" + requestParam.getEmbeddingModel()
                        + "\" 不可用: " + rootCause);
            }
        }

        // 使用统一 S3 桶 ragstudio，不再为每个知识库创建独立桶
        Integer supportsImageEmbedding = detectMultimodalEmbedding(requestParam.getEmbeddingModel());

        KnowledgeBaseDO kbDO = KnowledgeBaseDO.builder()
                .name(requestParam.getName())
                .description(requestParam.getDescription())
                .embeddingProvider(provider)
                .embeddingModel(requestParam.getEmbeddingModel())
                .dimension(dimension)
                .collectionName(requestParam.getCollectionName())
                .supportsImageEmbedding(supportsImageEmbedding)
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();

        knowledgeBaseMapper.insert(kbDO);

        VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                .spaceId(VectorSpaceId.builder()
                        .logicalName(requestParam.getCollectionName())
                        .build())
                .dimension(dimension)
                .remark(requestParam.getName())
                .build();
        vectorStoreAdmin.ensureVectorSpace(spaceSpec);

        return String.valueOf(kbDO.getId());
    }

    /**
     * 更新知识库
     * <p>若修改了嵌入模型，需校验知识库下不存在已向量化文档，否则拒绝修改。</p>
     */
    @Override
    public void update(KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(requestParam.getId());
        if (kb == null) {
            throw new ClientException("知识库不存在：" + requestParam.getId());
        }

        boolean embeddingChanged = StringUtils.hasText(requestParam.getEmbeddingModel())
                && !requestParam.getEmbeddingModel().equals(kb.getEmbeddingModel());
        if (embeddingChanged || StringUtils.hasText(requestParam.getEmbeddingProvider())) {
            Long docCount = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, requestParam.getId())
                            .gt(KnowledgeDocumentDO::getChunkCount, 0)
            );
            if (docCount > 0) {
                throw new ClientException("知识库已存在向量化文档，不允许修改嵌入模型");
            }
        }

        if (embeddingChanged) {
            kb.setEmbeddingModel(requestParam.getEmbeddingModel());
        }
        if (StringUtils.hasText(requestParam.getEmbeddingProvider())) {
            kb.setEmbeddingProvider(requestParam.getEmbeddingProvider());
        }

        if (StringUtils.hasText(requestParam.getName())) {
            kb.setName(requestParam.getName());
        }

        // 描述始终可修改（允许清空）
        if (requestParam.getDescription() != null) {
            kb.setDescription(requestParam.getDescription());
        }

        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);
    }

    /**
     * 重命名知识库，包含名称重复校验（排除当前知识库自身）
     */
    @Override
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new ClientException("知识库不存在");
        }

        if (!StringUtils.hasText(requestParam.getName())) {
            throw new ClientException("知识库名称不能为空");
        }

        // 名称重复校验（排除当前知识库）
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getName, name)
                        .ne(KnowledgeBaseDO::getId, kbId)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        kb.setName(requestParam.getName());
        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);

        log.info("成功重命名知识库, kbId={}, newName={}", kbId, requestParam.getName());
    }

    /**
     * 删除知识库
     * <p>
     * 处理流程：
     * 1. 校验知识库存在且无文档
     * 2. 物理删除数据库记录
     * 3. 清理 S3 存储桶和向量集合
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null) {
            throw new ClientException("知识库不存在");
        }

        Long docCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
        );
        if (docCount != null && docCount > 0) {
            throw new ClientException("当前知识库下还有文档，请删除文档");
        }

        knowledgeBaseMapper.deleteById(kbId);

        // 使用统一 S3 桶 ragstudio，不再按知识库独立创建桶，跳过 S3 桶清理
        // 清理向量集合
        cleanupVectorCollection(kbDO.getCollectionName());
    }

    /**
     * 清理 S3 存储桶：先删除桶内所有对象，再删除桶本身。
     * 清理失败不影响主流程（DB 已软删除），仅记录告警日志。
     */
    private void cleanupS3Bucket(String bucketName) {
        try {
            // 分页列出并删除桶内所有对象（S3 每次最多返回 1000 个对象）
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder listBuilder = ListObjectsV2Request.builder().bucket(bucketName);
                if (continuationToken != null) {
                    listBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response listResponse = s3Client.listObjectsV2(listBuilder.build());
                if (listResponse.contents() != null && !listResponse.contents().isEmpty()) {
                    List<ObjectIdentifier> objectIds = listResponse.contents().stream()
                            .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                            .collect(Collectors.toList());
                    DeleteObjectsResponse deleteResponse = s3Client.deleteObjects(
                            DeleteObjectsRequest.builder()
                                    .bucket(bucketName)
                                    .delete(builder -> builder.objects(objectIds))
                                    .build());
                    if (deleteResponse.hasErrors() && !deleteResponse.errors().isEmpty()) {
                        log.warn("清理 S3 存储桶部分对象失败, bucket={}, errors={}", bucketName, deleteResponse.errors());
                    }
                }
                continuationToken = Boolean.TRUE.equals(listResponse.isTruncated()) ? listResponse.nextContinuationToken() : null;
            } while (continuationToken != null);
            // 删除空桶
            s3Client.deleteBucket(builder -> builder.bucket(bucketName));
            log.info("成功清理 S3 存储桶, bucket={}", bucketName);
        } catch (Exception e) {
            log.warn("清理 S3 存储桶失败, bucket={}, 原因: {}", bucketName, e.getMessage(), e);
        }
    }

    /**
     * 校验并解析向量维度
     * <p>用户选择的 dimension 必须在模型支持的维度列表中且 ≤ 2000。</p>
     */
    private int validateAndResolveDimension(Integer selectedDimension, String embeddingModel) {
        if (embeddingModel == null) {
            throw new ServiceException("嵌入模型不能为空");
        }

        List<Integer> supported = null;
        try {
            AiModelDO model = aiModelMapper.selectOne(
                    Wrappers.lambdaQuery(AiModelDO.class)
                            .eq(AiModelDO::getModelId, embeddingModel)
                            .eq(AiModelDO::getDeleted, 0)
                            .last("LIMIT 1")
            );
            if (model == null) {
                throw new ServiceException("嵌入模型 \"" + embeddingModel + "\" 未在模型管理中找到，请先添加");
            }
            if (!"EMBEDDING".equalsIgnoreCase(model.getCapability())) {
                throw new ServiceException("模型 \"" + embeddingModel + "\" 能力类型为 " + model.getCapability()
                        + "，不是 EMBEDDING 类型，请选择正确的嵌入模型");
            }
            if (model.getDimension() != null) {
                supported = parseDimensionList(model.getDimension());
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("查询模型维度失败: model={}", embeddingModel, e);
        }

        if (supported == null || supported.isEmpty()) {
            log.warn("模型 {} 未配置维度列表，使用默认值 1536", embeddingModel);
            return 1536;
        }

        if (selectedDimension == null || selectedDimension <= 0) {
            int autoDim = supported.stream().max(Integer::compareTo).orElse(1536);
            log.info("未指定维度，自动选择 {} 维（模型支持: {}）", autoDim, supported);
            return autoDim;
        }

        // 校验用户选择的维度
        if (selectedDimension > 2000) {
            throw new ServiceException("pgvector 最多支持 2000 维，选择的维度 " + selectedDimension + " 不可用");
        }
        if (!supported.contains(selectedDimension)) {
            throw new ServiceException("模型 " + embeddingModel + " 不支持的维度: " + selectedDimension);
        }
        return selectedDimension;
    }

    /**
     * 将 JSON 字符串解析为整数列表
     */
    private List<Integer> parseDimensionList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            if (json.trim().startsWith("[")) {
                return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() {});
            }
            // 兼容旧数据：纯数字字符串
            int single = Integer.parseInt(json.trim());
            return List.of(single);
        } catch (Exception e) {
            log.warn("解析 dimension 失败: {}, 使用默认 [1536]", json);
            return List.of(1536);
        }
    }

    /**
     * 清理向量集合。
     * 清理失败不影响主流程（DB 已软删除），仅记录告警日志。
     */
    private void cleanupVectorCollection(String collectionName) {
        try {
            vectorStoreAdmin.deleteCollectionVectors(collectionName);
            log.info("向量集合已清理, collectionName={}", collectionName);
        } catch (Exception e) {
            log.warn("清理向量集合失败, collectionName={}, 原因: {}", collectionName, e.getMessage());
        }
    }

    /**
     * 根据 embedding modelId 从模型配置中推导 provider 名称
     */
    private String resolveEmbeddingProvider(String modelId) {
        try {
            AiModelDO model = aiModelMapper.selectOne(
                    Wrappers.lambdaQuery(AiModelDO.class)
                            .eq(AiModelDO::getModelId, modelId)
                            .last("LIMIT 1")
            );
            if (model == null) return null;
            AiProviderDO provider = aiProviderMapper.selectById(model.getProviderId());
            return provider != null ? provider.getName() : null;
        } catch (Exception e) {
            log.warn("推导 Embedding Provider 失败, modelId={}", modelId, e);
            return null;
        }
    }

    /**
     * 检测嵌入模型是否支持多模态（图像嵌入）
     */
    private Integer detectMultimodalEmbedding(String modelId) {
        if (modelId == null) return 0;
        try {
            AiModelDO model = aiModelMapper.selectOne(
                    Wrappers.lambdaQuery(AiModelDO.class)
                            .eq(AiModelDO::getModelId, modelId)
                            .eq(AiModelDO::getCapability, "EMBEDDING")
                            .eq(AiModelDO::getDeleted, 0)
                            .last("LIMIT 1")
            );
            if (model != null && model.getSupportsMultimodal() != null && model.getSupportsMultimodal() == 1) {
                log.info("嵌入模型 {} 支持多模态图像嵌入", modelId);
                return 1;
            }
        } catch (Exception e) {
            log.warn("检测嵌入模型多模态能力失败, modelId={}", modelId, e);
        }
        return 0;
    }

    // 根据 ID 查询知识库详情，不存在或已删除时抛出异常
    @Override
    public KnowledgeBaseVO queryById(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null) {
            throw new ClientException("知识库不存在");
        }
        return BeanUtil.toBean(kbDO, KnowledgeBaseVO.class);
    }

    /**
     * 分页查询知识库列表
     * <p>
     * 处理流程：
     * 1. 按名称模糊搜索分页查询知识库
     * 2. 批量统计每个知识库下的文档数量
     * 3. 将文档数量填充到返回结果中
     * </p>
     */
    @Override
    public IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam) {
        LambdaQueryWrapper<KnowledgeBaseDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                .like(StringUtils.hasText(requestParam.getName()), KnowledgeBaseDO::getName, requestParam.getName())
                .orderByDesc(KnowledgeBaseDO::getUpdateTime);

        Page<KnowledgeBaseDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeBaseDO> result = knowledgeBaseMapper.selectPage(page, queryWrapper);
        Map<String, Long> docCountMap = new HashMap<>();
        if (CollUtil.isNotEmpty(result.getRecords())) {
            List<String> kbIds = result.getRecords().stream()
                    .map(KnowledgeBaseDO::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!kbIds.isEmpty()) {
                List<Map<String, Object>> rows = knowledgeDocumentMapper.selectMaps(
                        Wrappers.query(KnowledgeDocumentDO.class)
                                .select("kb_id", "COUNT(1) AS doc_count")
                                .in("kb_id", kbIds)
                                .groupBy("kb_id")
                );
                for (Map<String, Object> row : rows) {
                    Object kbIdValue = row.get("kb_id");
                    Object countValue = row.get("doc_count");
                    if (kbIdValue == null || countValue == null) {
                        continue;
                    }
                    docCountMap.put(kbIdValue.toString(), ((Number) countValue).longValue());
                }
            }
        }
        return result.convert(each -> {
            KnowledgeBaseVO vo = BeanUtil.toBean(each, KnowledgeBaseVO.class);
            Long docCount = docCountMap.get(each.getId());
            vo.setDocumentCount(docCount != null ? docCount : 0L);
            return vo;
        });
    }
}
