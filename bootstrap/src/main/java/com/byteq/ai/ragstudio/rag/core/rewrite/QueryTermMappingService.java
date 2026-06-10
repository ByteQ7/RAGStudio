package com.byteq.ai.ragstudio.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteq.ai.ragstudio.rag.dao.entity.QueryTermMappingDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.QueryTermMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTermMappingService {

    private final QueryTermMappingMapper mappingMapper;
    private final QueryTermMappingCacheManager cacheManager;
    private final ObjectMapper objectMapper;

    /**
     * 对用户问题做术语归一化（不指定知识库时应用所有映射规则，向后兼容）
     */
    public String normalize(String text) {
        return doNormalize(text, null);
    }

    /**
     * 对用户问题做术语归一化，根据知识库 ID 过滤映射规则
     * <ul>
     *   <li>knowledgeBaseIds 为 null：应用所有映射规则（向后兼容，用于不传知识库上下文的场景）</li>
     *   <li>knowledgeBaseIds 为空列表：不做任何归一化（用户未选择知识库）</li>
     *   <li>knowledgeBaseIds 非空：仅应用与指定知识库有交集的映射规则</li>
     * </ul>
     *
     * @param text             用户原始问题文本
     * @param knowledgeBaseIds 用户选定的知识库 ID 列表
     */
    public String normalize(String text, List<String> knowledgeBaseIds) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 用户未选择任何知识库 → 不做归一化
        if (knowledgeBaseIds != null && knowledgeBaseIds.isEmpty()) {
            return text;
        }
        return doNormalize(text, knowledgeBaseIds);
    }

    /**
     * 内部归一化实现
     *
     * @param text             原始文本
     * @param knowledgeBaseIds null 表示应用所有映射；非空列表表示按知识库交集过滤
     */
    private String doNormalize(String text, List<String> knowledgeBaseIds) {
        List<QueryTermMappingDO> mappings = loadMappings();
        if (mappings.isEmpty()) {
            return text;
        }

        String result = text;
        for (QueryTermMappingDO mapping : mappings) {
            if (mapping.getEnabled() == null || mapping.getEnabled() == 0) {
                continue;
            }
            if (mapping.getMatchType() != null && mapping.getMatchType() != 1) {
                continue;
            }

            // 当指定了知识库 ID 时，检查映射关联的知识库是否与用户选定的知识库有交集
            if (knowledgeBaseIds != null && !isMappingApplicable(mapping, knowledgeBaseIds)) {
                continue;
            }

            String source = mapping.getSourceTerm();
            String target = mapping.getTargetTerm();
            if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
                continue;
            }
            result = QueryTermMappingUtil.applyMapping(result, source, target);
        }

        if (!Objects.equals(text, result)) {
            log.info("查询归一化：original='{}', normalized='{}', knowledgeBaseIds={}", text, result, knowledgeBaseIds);
        }
        return result;
    }

    /**
     * 判断映射规则是否适用于当前查询（根据知识库 ID 交集判断）
     * <p>
     * 规则：
     * - 映射关联的知识库 ID 列表与用户选定的知识库 ID 列表存在交集 → 适用
     * - 映射未关联任何知识库（空/空白）→ 不适用（视为未配置知识库的规则，不应被应用）
     */
    private boolean isMappingApplicable(QueryTermMappingDO mapping, List<String> knowledgeBaseIds) {
        List<String> mappingKbIds = parseKnowledgeBaseIds(mapping.getKnowledgeBaseIds());
        if (CollUtil.isEmpty(mappingKbIds)) {
            // 未关联知识库的映射规则不应用
            return false;
        }
        // 检查是否有交集
        return mappingKbIds.stream().anyMatch(knowledgeBaseIds::contains);
    }

    /**
     * 解析 JSON 格式的知识库 ID 列表
     */
    private List<String> parseKnowledgeBaseIds(String json) {
        if (StrUtil.isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析映射规则的知识库ID列表失败: {}", json, e);
            return Collections.emptyList();
        }
    }

    /**
     * 加载映射规则：优先从 Redis 缓存读取，缓存未命中则从数据库加载并回填缓存
     */
    private List<QueryTermMappingDO> loadMappings() {
        List<QueryTermMappingDO> cached = cacheManager.getMappingsFromCache();
        if (CollUtil.isNotEmpty(cached)) {
            return cached;
        }

        // 缓存未命中，从数据库加载
        List<QueryTermMappingDO> dbList = mappingMapper.selectList(
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .eq(QueryTermMappingDO::getEnabled, 1)
        );
        dbList.sort(Comparator
                .comparing(QueryTermMappingDO::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed()
                .thenComparing(m -> m.getSourceTerm() == null ? 0 : m.getSourceTerm().length(), Comparator.reverseOrder())
        );

        // 回填 Redis 缓存
        cacheManager.saveMappingsToCache(dbList);
        log.info("术语映射规则从数据库加载完成，共 {} 条规则", dbList.size());
        return dbList;
    }
}
