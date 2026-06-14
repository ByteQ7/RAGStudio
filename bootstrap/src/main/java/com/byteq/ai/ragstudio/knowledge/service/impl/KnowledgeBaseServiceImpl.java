package com.byteq.ai.ragstudio.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeBaseService;
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

    @Transactional(rollbackFor = Exception.class)
    @Override
    public String create(KnowledgeBaseCreateRequest requestParam) {
        // 名称重复校验
        String name = requestParam.getName().replaceAll("\\s+", "");
        Long count = knowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KnowledgeBaseDO>()
                        .eq(KnowledgeBaseDO::getName, name)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        String bucketName = requestParam.getCollectionName();

        // 先创建 S3 桶，再插入 DB。若 S3 失败则 DB 事务不会提交，不产生孤儿记录。
        try {
            s3Client.createBucket(builder -> builder.bucket(bucketName));
            log.info("成功创建RestFS存储桶，Bucket名称: {}", bucketName);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            if (e instanceof BucketAlreadyOwnedByYouException) {
                log.error("RestFS存储桶已存在，Bucket名称: {}", bucketName, e);
            } else {
                log.error("RestFS存储桶已存在但由其他账户拥有，Bucket名称: {}", bucketName, e);
            }
            throw new ServiceException("存储桶名称已被占用：" + bucketName);
        }

        try {
            KnowledgeBaseDO kbDO = KnowledgeBaseDO.builder()
                    .name(requestParam.getName())
                    .embeddingModel(requestParam.getEmbeddingModel())
                    .collectionName(requestParam.getCollectionName())
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .deleted(0)
                    .build();

            knowledgeBaseMapper.insert(kbDO);

            VectorSpaceSpec spaceSpec = VectorSpaceSpec.builder()
                    .spaceId(VectorSpaceId.builder()
                            .logicalName(requestParam.getCollectionName())
                            .build())
                    .remark(requestParam.getName())
                    .build();
            vectorStoreAdmin.ensureVectorSpace(spaceSpec);

            return String.valueOf(kbDO.getId());
        } catch (Exception e) {
            // DB 插入或向量空间创建失败，补偿删除已创建的 S3 桶，避免孤儿桶
            log.warn("知识库创建失败，正在补偿删除 S3 存储桶, bucket={}", bucketName);
            try {
                s3Client.deleteBucket(builder -> builder.bucket(bucketName));
            } catch (Exception s3Ex) {
                log.error("补偿删除 S3 存储桶失败, bucket={}", bucketName, s3Ex);
            }
            throw e;
        }
    }

    @Override
    public void update(KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(requestParam.getId());
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
            throw new ClientException("知识库不存在：" + requestParam.getId());
        }

        if (StringUtils.hasText(requestParam.getEmbeddingModel())
                && !requestParam.getEmbeddingModel().equals(kb.getEmbeddingModel())) {

            Long docCount = knowledgeDocumentMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeDocumentDO>()
                            .eq(KnowledgeDocumentDO::getKbId, requestParam.getId())
                            .gt(KnowledgeDocumentDO::getChunkCount, 0)
                            .eq(KnowledgeDocumentDO::getDeleted, 0)
            );
            if (docCount > 0) {
                throw new ClientException("知识库已存在向量化文档，不允许修改嵌入模型");
            }

            kb.setEmbeddingModel(requestParam.getEmbeddingModel());
        }

        if (StringUtils.hasText(requestParam.getName())) {
            kb.setName(requestParam.getName());
        }

        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);
    }

    @Override
    public void rename(String kbId, KnowledgeBaseUpdateRequest requestParam) {
        KnowledgeBaseDO kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null || kb.getDeleted() != null && kb.getDeleted() == 1) {
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
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (count > 0) {
            throw new ServiceException("知识库名称已存在：" + requestParam.getName());
        }

        kb.setName(requestParam.getName());
        kb.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.updateById(kb);

        log.info("成功重命名知识库, kbId={}, newName={}", kbId, requestParam.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }

        Long docCount = knowledgeDocumentMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeDocumentDO.class)
                        .eq(KnowledgeDocumentDO::getKbId, kbId)
                        .eq(KnowledgeDocumentDO::getDeleted, 0)
        );
        if (docCount != null && docCount > 0) {
            throw new ClientException("当前知识库下还有文档，请删除文档");
        }

        kbDO.setDeleted(1);
        kbDO.setUpdatedBy(UserContext.getUsername());
        knowledgeBaseMapper.deleteById(kbDO);

        // 清理 S3 存储桶（先清空对象，再删除桶）
        String bucketName = kbDO.getCollectionName();
        cleanupS3Bucket(bucketName);

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
     * 清理向量集合。
     * 清理失败不影响主流程（DB 已软删除），仅记录告警日志。
     */
    private void cleanupVectorCollection(String collectionName) {
        try {
            VectorSpaceId spaceId = VectorSpaceId.builder()
                    .logicalName(collectionName)
                    .build();
            if (vectorStoreAdmin.vectorSpaceExists(spaceId)) {
                // 当前 VectorStoreAdmin 接口未提供删除方法，记录告警供运维人工处理
                log.warn("向量集合需要清理但 VectorStoreAdmin 暂不支持删除, collectionName={}, 请人工清理", collectionName);
            }
        } catch (Exception e) {
            log.warn("检查/清理向量集合失败, collectionName={}, 原因: {}", collectionName, e.getMessage(), e);
        }
    }

    @Override
    public KnowledgeBaseVO queryById(String kbId) {
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        if (kbDO == null || kbDO.getDeleted() != null && kbDO.getDeleted() == 1) {
            throw new ClientException("知识库不存在");
        }
        return BeanUtil.toBean(kbDO, KnowledgeBaseVO.class);
    }

    @Override
    public IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam) {
        LambdaQueryWrapper<KnowledgeBaseDO> queryWrapper = Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                .like(StringUtils.hasText(requestParam.getName()), KnowledgeBaseDO::getName, requestParam.getName())
                .eq(KnowledgeBaseDO::getDeleted, 0)
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
                                .eq("deleted", 0)
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
