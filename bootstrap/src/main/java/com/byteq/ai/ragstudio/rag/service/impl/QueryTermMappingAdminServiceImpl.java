package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingPageRequest;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.QueryTermMappingVO;
import com.byteq.ai.ragstudio.rag.core.rewrite.QueryTermMappingCacheManager;
import com.byteq.ai.ragstudio.rag.dao.entity.QueryTermMappingDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.QueryTermMappingMapper;
import com.byteq.ai.ragstudio.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 查询词条映射管理服务实现类
 * <p>
 * 实现映射规则的 CRUD 操作，每次写操作后清除缓存以保证查询一致性。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTermMappingAdminServiceImpl implements QueryTermMappingAdminService {

    private final QueryTermMappingMapper queryTermMappingMapper;
    private final QueryTermMappingCacheManager queryTermMappingCacheManager;
    private final ObjectMapper objectMapper;

    // 创建映射规则: 校验参数 -> 构建实体 -> 插入数据库 -> 清除缓存
    @Override
    public String create(QueryTermMappingCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
        String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
        Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
        Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));
        Assert.isTrue(CollUtil.isNotEmpty(requestParam.getKnowledgeBaseIds()), () -> new ClientException("必须选择至少一个关联知识库"));

        QueryTermMappingDO record = new QueryTermMappingDO();
        record.setSourceTerm(sourceTerm);
        record.setTargetTerm(targetTerm);
        record.setMatchType(requestParam.getMatchType() != null ? requestParam.getMatchType() : 1);
        record.setPriority(requestParam.getPriority() != null ? requestParam.getPriority() : 0);
        record.setEnabled(requestParam.getEnabled() != null ? (requestParam.getEnabled() ? 1 : 0) : 1);
        record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));
        record.setKnowledgeBaseIds(toJsonString(requestParam.getKnowledgeBaseIds()));

        queryTermMappingMapper.insert(record);
        queryTermMappingCacheManager.clearCache();
        return String.valueOf(record.getId());
    }

    // 更新映射规则: 加载记录 -> 按非空字段逐个更新 -> 写回数据库 -> 清除缓存
    @Override
    public void update(String id, QueryTermMappingUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        QueryTermMappingDO record = loadById(id);

        if (requestParam.getSourceTerm() != null) {
            String sourceTerm = StrUtil.trimToNull(requestParam.getSourceTerm());
            Assert.notBlank(sourceTerm, () -> new ClientException("原始词不能为空"));
            record.setSourceTerm(sourceTerm);
        }
        if (requestParam.getTargetTerm() != null) {
            String targetTerm = StrUtil.trimToNull(requestParam.getTargetTerm());
            Assert.notBlank(targetTerm, () -> new ClientException("目标词不能为空"));
            record.setTargetTerm(targetTerm);
        }
        if (requestParam.getMatchType() != null) {
            record.setMatchType(requestParam.getMatchType());
        }
        if (requestParam.getPriority() != null) {
            record.setPriority(requestParam.getPriority());
        }
        if (requestParam.getEnabled() != null) {
            record.setEnabled(requestParam.getEnabled() ? 1 : 0);
        }
        if (requestParam.getRemark() != null) {
            record.setRemark(StrUtil.trimToNull(requestParam.getRemark()));
        }
        if (requestParam.getKnowledgeBaseIds() != null) {
            Assert.isTrue(CollUtil.isNotEmpty(requestParam.getKnowledgeBaseIds()), () -> new ClientException("必须选择至少一个关联知识库"));
            record.setKnowledgeBaseIds(toJsonString(requestParam.getKnowledgeBaseIds()));
        }

        queryTermMappingMapper.updateById(record);
        queryTermMappingCacheManager.clearCache();
    }

    // 删除映射规则并清除缓存
    @Override
    public void delete(String id) {
        QueryTermMappingDO record = loadById(id);
        queryTermMappingMapper.deleteById(record.getId());
        queryTermMappingCacheManager.clearCache();
    }

    // 根据 ID 查询映射规则详情并转换为 VO
    @Override
    public QueryTermMappingVO queryById(String id) {
        QueryTermMappingDO record = loadById(id);
        return toVO(record);
    }

    // 分页查询映射规则，支持按源词条/目标词条关键字模糊搜索，按优先级升序、更新时间降序
    @Override
    public IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<QueryTermMappingDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<QueryTermMappingDO> result = queryTermMappingMapper.selectPage(
                page,
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(QueryTermMappingDO::getSourceTerm, keyword)
                                .or()
                                .like(QueryTermMappingDO::getTargetTerm, keyword))
                        .orderByAsc(QueryTermMappingDO::getPriority)
                        .orderByDesc(QueryTermMappingDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    // 根据 ID 加载映射规则，不存在时抛出异常
    private QueryTermMappingDO loadById(String id) {
        QueryTermMappingDO record = queryTermMappingMapper.selectById(id);
        Assert.notNull(record, () -> new ClientException("映射规则不存在"));
        return record;
    }

    // 将映射规则 DO 转换为 VO，包含知识库 ID 列表的反序列化
    private QueryTermMappingVO toVO(QueryTermMappingDO record) {
        return QueryTermMappingVO.builder()
                .id(String.valueOf(record.getId()))
                .sourceTerm(record.getSourceTerm())
                .targetTerm(record.getTargetTerm())
                .matchType(record.getMatchType())
                .priority(record.getPriority())
                .enabled(record.getEnabled() != null && record.getEnabled() == 1)
                .remark(record.getRemark())
                .knowledgeBaseIds(parseKnowledgeBaseIds(record.getKnowledgeBaseIds()))
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }

    /**
     * 将知识库 ID 列表序列化为 JSON 字符串
     */
    private String toJsonString(List<String> knowledgeBaseIds) {
        if (CollUtil.isEmpty(knowledgeBaseIds)) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(knowledgeBaseIds);
        } catch (JsonProcessingException e) {
            log.error("序列化知识库ID列表失败", e);
            throw new ClientException("知识库ID格式错误");
        }
    }

    /**
     * 将 JSON 字符串反序列化为知识库 ID 列表
     */
    private List<String> parseKnowledgeBaseIds(String json) {
        if (StrUtil.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("解析知识库ID列表失败: {}", json, e);
            return Collections.emptyList();
        }
    }
}
