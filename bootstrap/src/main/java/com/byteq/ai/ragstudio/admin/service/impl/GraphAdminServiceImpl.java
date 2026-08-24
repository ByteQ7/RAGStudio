package com.byteq.ai.ragstudio.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphBuildLogVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphEntityVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphKbStatusVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphOverviewVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphRelationVO;
import com.byteq.ai.ragstudio.admin.controller.vo.GraphSubgraphVO;
import com.byteq.ai.ragstudio.admin.service.GraphAdminService;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.graph.config.GraphConfigService;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphBuildLogDO;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphEntityDO;
import com.byteq.ai.ragstudio.graph.dao.entity.GraphRelationDO;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphBuildLogMapper;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphEntityMapper;
import com.byteq.ai.ragstudio.graph.dao.mapper.GraphRelationMapper;
import com.byteq.ai.ragstudio.graph.service.GraphExtractionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图谱管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphAdminServiceImpl implements GraphAdminService {

    private final GraphEntityMapper entityMapper;
    private final GraphRelationMapper relationMapper;
    private final GraphBuildLogMapper buildLogMapper;
    private final GraphExtractionService graphExtractionService;
    private final GraphConfigService graphConfigService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final int MAX_SUBGRAPH_NODES = 200;

    @Override
    public GraphOverviewVO overview(String kbId) {
        Long entityCount = entityMapper.selectCount(kbEntityWrapper(kbId));
        Long relationCount = relationMapper.selectCount(new LambdaQueryWrapper<GraphRelationDO>()
                .eq(GraphRelationDO::getKbId, kbId));
        Long extractionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_graph_extraction WHERE kb_id = ?", Long.class, kbId);
        Long failedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_graph_extraction WHERE kb_id = ? AND status = 'FAILED'", Long.class, kbId);
        String lastBuildTime = jdbcTemplate.query("""
                        SELECT to_char(MAX(create_time), 'YYYY-MM-DD HH24:MI:SS')
                        FROM t_graph_build_log WHERE kb_id = ?
                        """,
                rs -> rs.next() ? rs.getString(1) : null, kbId);
        return GraphOverviewVO.builder()
                .graphEnabled(graphConfigService.isEnabled())
                .entityCount(entityCount == null ? 0 : entityCount)
                .relationCount(relationCount == null ? 0 : relationCount)
                .extractionCount(extractionCount == null ? 0 : extractionCount)
                .failedCount(failedCount == null ? 0 : failedCount)
                .lastBuildTime(lastBuildTime)
                .build();
    }

    @Override
    public List<GraphKbStatusVO> kbsStatus() {
        List<GraphKbStatusVO> result = new ArrayList<>();
        List<Map<String, Object>> kbs;
        try {
            kbs = jdbcTemplate.queryForList("SELECT id, name FROM t_knowledge_base ORDER BY create_time DESC");
        } catch (Exception e) {
            log.debug("查询知识库列表失败（可能未执行 V3 SQL）: {}", e.getMessage());
            return result;
        }
        for (Map<String, Object> kb : kbs) {
            String kbId = String.valueOf(kb.get("id"));
            try {
                GraphOverviewVO overview = overview(kbId);
                result.add(GraphKbStatusVO.builder()
                        .kbId(kbId)
                        .kbName(kb.get("name") == null ? kbId : String.valueOf(kb.get("name")))
                        .entityCount(overview.getEntityCount())
                        .relationCount(overview.getRelationCount())
                        .extractionCount(overview.getExtractionCount())
                        .failedCount(overview.getFailedCount())
                        .lastBuildTime(overview.getLastBuildTime())
                        .build());
            } catch (Exception e) {
                log.debug("查询知识库图谱状态失败（可能未执行 V3 SQL）: kbId={}, err={}", kbId, e.getMessage());
                result.add(GraphKbStatusVO.builder()
                        .kbId(kbId)
                        .kbName(kb.get("name") == null ? kbId : String.valueOf(kb.get("name")))
                        .entityCount(0L).relationCount(0L).extractionCount(0L).failedCount(0L)
                        .build());
            }
        }
        return result;
    }

    @Override
    public IPage<GraphEntityVO> pageEntities(String kbId, String keyword, String entityType, long current, long size) {
        LambdaQueryWrapper<GraphEntityDO> wrapper = new LambdaQueryWrapper<GraphEntityDO>()
                .eq(GraphEntityDO::getKbId, kbId)
                .like(StrUtil.isNotBlank(keyword), GraphEntityDO::getCanonicalName, keyword)
                .eq(StrUtil.isNotBlank(entityType), GraphEntityDO::getEntityType, entityType)
                .orderByDesc(GraphEntityDO::getCreateTime);
        Page<GraphEntityDO> page = new Page<>(current, size);
        IPage<GraphEntityDO> result = entityMapper.selectPage(page, wrapper);
        return result.convert(this::toEntityVO);
    }

    @Override
    public GraphEntityVO getEntity(String entityId) {
        GraphEntityDO entity = entityMapper.selectById(entityId);
        if (entity == null) {
            throw new ClientException("实体不存在");
        }
        return toEntityVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void mergeEntities(String kbId, String keepEntityId, List<String> mergeEntityIds) {
        GraphEntityDO keep = entityMapper.selectById(keepEntityId);
        if (keep == null || !kbId.equals(keep.getKbId())) {
            throw new ClientException("保留实体不存在");
        }
        if (mergeEntityIds == null || mergeEntityIds.isEmpty()) {
            throw new ClientException("请指定需要合并的实体");
        }
        if (mergeEntityIds.contains(keepEntityId)) {
            throw new ClientException("保留实体不能出现在被合并列表中");
        }
        for (String mergeId : mergeEntityIds) {
            GraphEntityDO merge = entityMapper.selectById(mergeId);
            if (merge == null || !kbId.equals(merge.getKbId())) {
                throw new ClientException("被合并实体不存在: " + mergeId);
            }
            // 1. 关系重指向
            jdbcTemplate.update(
                    "UPDATE t_graph_relation SET source_entity_id = ? WHERE kb_id = ? AND source_entity_id = ?",
                    keepEntityId, kbId, mergeId);
            jdbcTemplate.update(
                    "UPDATE t_graph_relation SET target_entity_id = ? WHERE kb_id = ? AND target_entity_id = ?",
                    keepEntityId, kbId, mergeId);
            // 2. 合并别名
            Set<String> aliases = parseAliases(keep.getAliases());
            aliases.addAll(parseAliases(merge.getAliases()));
            aliases.add(merge.getDisplayName());
            keep.setAliases(toJson(aliases));
            if (!StringUtils.hasText(keep.getDescription()) && StringUtils.hasText(merge.getDescription())) {
                keep.setDescription(merge.getDescription());
            }
            // 3. 删除被合并实体（其关系已迁移，不会被误删）
            entityMapper.deleteById(mergeId);
        }
        entityMapper.updateById(keep);
        log.info("图谱实体合并完成: kbId={}, keep={}, merged={}", kbId, keepEntityId, mergeEntityIds.size());
    }

    @Override
    public GraphSubgraphVO subgraph(String kbId, String focusEntityId, int maxNodes) {
        int limit = Math.min(Math.max(maxNodes > 0 ? maxNodes : MAX_SUBGRAPH_NODES, 10), MAX_SUBGRAPH_NODES);
        Set<String> entityIds = new LinkedHashSet<>();
        if (StringUtils.hasText(focusEntityId)) {
            // 定点展开：BFS 两跳
            Set<String> frontier = Set.of(focusEntityId);
            entityIds.add(focusEntityId);
            for (int depth = 0; depth < 2 && !frontier.isEmpty(); depth++) {
                Set<String> next = new HashSet<>();
                List<String> neighborIds = queryNeighbors(kbId, frontier, 50);
                for (String id : neighborIds) {
                    if (entityIds.size() >= limit) {
                        break;
                    }
                    if (entityIds.add(id)) {
                        next.add(id);
                    }
                }
                frontier = next;
            }
        } else {
            // 高频实体：按关系数取 topN
            entityIds.addAll(jdbcTemplate.query("""
                            SELECT source_entity_id AS id FROM t_graph_relation WHERE kb_id = ?
                            GROUP BY source_entity_id ORDER BY COUNT(*) DESC LIMIT ?
                            """,
                    (rs, i) -> rs.getString("id"), kbId, Math.min(limit, 100)));
        }
        if (entityIds.isEmpty()) {
            return GraphSubgraphVO.builder().nodes(List.of()).links(List.of()).truncated(false).build();
        }

        Map<String, GraphEntityDO> entityById = entityMapper.selectByIds(new ArrayList<>(entityIds)).stream()
                .collect(Collectors.toMap(GraphEntityDO::getId, e -> e, (a, b) -> a));

        // 子图内边（两端都在集合内）
        List<GraphSubgraphVO.Link> links = new ArrayList<>();
        Set<String> seenLinks = new HashSet<>();
        if (!entityIds.isEmpty()) {
            List<String> idList = new ArrayList<>(entityIds);
            jdbcTemplate.query("""
                            SELECT source_entity_id, target_entity_id, predicate
                            FROM t_graph_relation
                            WHERE kb_id = ? AND source_entity_id = ANY(?) AND target_entity_id = ANY(?)
                            ORDER BY weight DESC LIMIT 500
                            """,
                    (rs, i) -> {
                        String src = rs.getString("source_entity_id");
                        String tgt = rs.getString("target_entity_id");
                        String predicate = rs.getString("predicate");
                        String key = src + "|" + predicate + "|" + tgt;
                        if (seenLinks.add(key) && entityById.containsKey(src) && entityById.containsKey(tgt)) {
                            links.add(GraphSubgraphVO.Link.builder()
                                    .source(src).target(tgt).predicate(predicate).build());
                        }
                        return null;
                    },
                    kbId, idList.toArray(new String[0]), idList.toArray(new String[0]));
        }

        boolean truncated = entityIds.size() >= limit || links.size() >= 500;
        List<GraphSubgraphVO.Node> nodes = entityIds.stream()
                .map(id -> {
                    GraphEntityDO e = entityById.get(id);
                    if (e == null) {
                        return null;
                    }
                    return GraphSubgraphVO.Node.builder()
                            .id(e.getId()).name(e.getDisplayName()).type(e.getEntityType()).build();
                })
                .filter(n -> n != null)
                .toList();
        return GraphSubgraphVO.builder().nodes(nodes).links(links).truncated(truncated).build();
    }

    @Override
    public IPage<GraphBuildLogVO> pageBuildLogs(String kbId, long current, long size) {
        LambdaQueryWrapper<GraphBuildLogDO> wrapper = new LambdaQueryWrapper<GraphBuildLogDO>()
                .eq(GraphBuildLogDO::getKbId, kbId)
                .orderByDesc(GraphBuildLogDO::getCreateTime);
        Page<GraphBuildLogDO> page = new Page<>(current, size);
        IPage<GraphBuildLogDO> result = buildLogMapper.selectPage(page, wrapper);
        return result.convert(e -> {
            GraphBuildLogVO vo = new GraphBuildLogVO();
            vo.setId(e.getId());
            vo.setTriggerType(e.getTriggerType());
            vo.setDocId(e.getDocId());
            vo.setStatus(e.getStatus());
            vo.setEntityAdded(e.getEntityAdded());
            vo.setEntityMerged(e.getEntityMerged());
            vo.setRelationAdded(e.getRelationAdded());
            vo.setRelationRemoved(e.getRelationRemoved());
            vo.setLlmCalls(e.getLlmCalls());
            vo.setDurationMs(e.getDurationMs());
            vo.setErrorMessage(e.getErrorMessage());
            vo.setCreateTime(formatTime(e.getCreateTime()));
            return vo;
        });
    }

    private List<String> queryNeighbors(String kbId, Set<String> frontier, int limit) {
        List<String> result = new ArrayList<>();
        jdbcTemplate.query("""
                        SELECT r.target_entity_id AS id FROM t_graph_relation r
                        WHERE r.kb_id = ? AND r.source_entity_id = ANY(?) ORDER BY r.weight DESC LIMIT ?
                        """,
                (rs, i) -> rs.getString("id"), kbId, frontier.toArray(new String[0]), limit);
        result.addAll(jdbcTemplate.query("""
                        SELECT r.source_entity_id AS id FROM t_graph_relation r
                        WHERE r.kb_id = ? AND r.target_entity_id = ANY(?) ORDER BY r.weight DESC LIMIT ?
                        """,
                (rs, i) -> rs.getString("id"), kbId, frontier.toArray(new String[0]), limit));
        return result;
    }

    private GraphEntityVO toEntityVO(GraphEntityDO entity) {
        Long relationCount = relationMapper.selectCount(new LambdaQueryWrapper<GraphRelationDO>()
                .eq(GraphRelationDO::getSourceEntityId, entity.getId())
                .or().eq(GraphRelationDO::getTargetEntityId, entity.getId()));
        return GraphEntityVO.builder()
                .id(entity.getId())
                .canonicalName(entity.getCanonicalName())
                .displayName(entity.getDisplayName())
                .entityType(entity.getEntityType())
                .description(entity.getDescription())
                .aliases(new ArrayList<>(parseAliases(entity.getAliases())))
                .relationCount(relationCount == null ? 0 : relationCount)
                .createTime(formatTime(entity.getCreateTime()))
                .build();
    }

    private LambdaQueryWrapper<GraphEntityDO> kbEntityWrapper(String kbId) {
        return new LambdaQueryWrapper<GraphEntityDO>().eq(GraphEntityDO::getKbId, kbId);
    }

    private Set<String> parseAliases(String aliasesJson) {
        if (!StringUtils.hasText(aliasesJson)) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(objectMapper.readValue(aliasesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String formatTime(java.util.Date date) {
        return date == null ? null : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }
}