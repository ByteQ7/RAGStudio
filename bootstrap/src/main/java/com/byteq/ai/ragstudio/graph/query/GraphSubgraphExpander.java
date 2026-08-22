package com.byteq.ai.ragstudio.graph.query;

import com.byteq.ai.ragstudio.graph.config.GraphProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 子图展开器
 * <p>以命中实体为种子，双向 K 跳 BFS 展开子图（递归 CTE 的 Java 实现），
 * 逐跳限制邻居数与总节点数，防 hub 节点爆炸；分数沿路径按深度衰减。</p>
 */
@Slf4j
@Component
public class GraphSubgraphExpander {

    private final GraphProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public GraphSubgraphExpander(GraphProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 子图展开结果
     *
     * @param nodes 展开节点（含深度与分数）
     * @param edges 展开边（含深度、分数与证据 chunk）
     */
    public record ExpandResult(List<GraphNode> nodes, List<GraphEdge> edges) {

        public boolean isEmpty() {
            return edges.isEmpty();
        }
    }

    /**
     * 子图节点
     */
    public record GraphNode(String entityId, String canonicalName, String displayName,
                            String entityType, int depth, double score) {
    }

    /**
     * 子图边
     *
     * @param sourceId 源实体 ID
     * @param sourceName 源实体名
     * @param targetId 目标实体 ID
     * @param targetName 目标实体名
     * @param predicate 关系谓词
     * @param evidence 证据文本（可为空）
     * @param chunkId 证据 chunk ID（可为空）
     * @param depth 边深度（种子实体为 0）
     * @param score 边分数（深度衰减后的种子匹配分）
     */
    public record GraphEdge(String sourceId, String sourceName, String targetId, String targetName,
                            String predicate, String evidence, String chunkId, int depth, double score) {
    }

    /**
     * 执行子图展开
     *
     * @param kbId 知识库 ID
     * @param seeds 命中实体（含匹配分）
     * @return 展开结果（节点 + 边）
     */
    public ExpandResult expand(String kbId, List<GraphQueryEntityExtractor.QueryEntity> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            return new ExpandResult(List.of(), List.of());
        }
        GraphProperties.Retrieval config = properties.getRetrieval();
        int maxDepth = Math.max(1, config.getMaxDepth());
        int perHopLimit = Math.max(1, config.getMaxNeighborsPerHop());
        int maxNodes = Math.max(1, config.getMaxNodes());

        Map<String, Double> nodeScores = new HashMap<>();
        Map<String, GraphNode> nodesById = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();

        // 种子节点
        Set<String> frontier = new HashSet<>();
        for (GraphQueryEntityExtractor.QueryEntity seed : seeds) {
            nodeScores.put(seed.entityId(), seed.score());
            nodesById.put(seed.entityId(), new GraphNode(seed.entityId(), seed.canonicalName(),
                    seed.displayName(), "SEED", 0, seed.score()));
            frontier.add(seed.entityId());
        }
        Set<String> visited = new HashSet<>(frontier);

        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            // 每跳从当前前沿向外、向里各展开 perHopLimit 条
            List<RawEdge> outgoing = queryOutgoing(kbId, frontier, perHopLimit);
            List<RawEdge> incoming = queryIncoming(kbId, frontier, perHopLimit);

            Set<String> nextFrontier = new HashSet<>();
            for (RawEdge edge : outgoing) {
                double parentScore = nodeScores.getOrDefault(edge.sourceId(), 0D);
                if (parentScore <= 0) {
                    continue;
                }
                double edgeScore = decay(parentScore, depth);
                edges.add(toEdge(edge, edgeScore, depth));
                if (!visited.contains(edge.targetId())) {
                    visited.add(edge.targetId());
                    nodeScores.put(edge.targetId(), edgeScore);
                    nodesById.put(edge.targetId(), edge.target());
                    nextFrontier.add(edge.targetId());
                }
            }
            for (RawEdge edge : incoming) {
                double parentScore = nodeScores.getOrDefault(edge.targetId(), 0D);
                if (parentScore <= 0) {
                    continue;
                }
                double edgeScore = decay(parentScore, depth);
                edges.add(toEdge(edge, edgeScore, depth));
                if (!visited.contains(edge.sourceId())) {
                    visited.add(edge.sourceId());
                    nodeScores.put(edge.sourceId(), edgeScore);
                    nodesById.put(edge.sourceId(), edge.source());
                    nextFrontier.add(edge.sourceId());
                }
            }
            frontier = nextFrontier;
            if (nodesById.size() >= maxNodes) {
                log.debug("子图展开达节点上限({})，提前终止: kbId={}", maxNodes, kbId);
                break;
            }
        }
        edges.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new ExpandResult(new ArrayList<>(nodesById.values()), edges);
    }

    /** 深度衰减：种子匹配分随跳数递减 */
    private double decay(double seedScore, int depth) {
        return seedScore / (1.0 + depth);
    }

    /** 出边查询：source ∈ frontier → target */
    private List<RawEdge> queryOutgoing(String kbId, Set<String> frontier, int limit) {
        List<RawEdge> result = new ArrayList<>();
        try {
            result = jdbcTemplate.query("""
                            SELECT r.source_entity_id, s.canonical_name AS s_name, s.display_name AS s_display,
                                   s.entity_type AS s_type,
                                   r.target_entity_id, e.canonical_name AS t_name, e.display_name AS t_display,
                                   e.entity_type AS t_type,
                                   r.predicate, r.evidence, r.source_chunk_id, r.weight
                            FROM t_graph_relation r
                            JOIN t_graph_entity s ON s.id = r.source_entity_id
                            JOIN t_graph_entity e ON e.id = r.target_entity_id
                            WHERE r.kb_id = ? AND r.source_entity_id = ANY(?)
                            ORDER BY r.weight DESC
                            LIMIT ?
                            """,
                    (rs, rowNum) -> new RawEdge(
                            rs.getString("source_entity_id"),
                            rs.getString("target_entity_id"),
                            rs.getString("predicate"),
                            rs.getString("evidence"),
                            rs.getString("source_chunk_id"),
                            rs.getDouble("weight"),
                            node(rs.getString("s_name"), rs.getString("s_display"), rs.getString("s_type")),
                            node(rs.getString("t_name"), rs.getString("t_display"), rs.getString("t_type"))),
                    kbId, frontier.toArray(new String[0]), limit);
        } catch (Exception e) {
            log.warn("子图出边查询失败: {}", e.getMessage());
        }
        return result;
    }

    /** 入边查询：target ∈ frontier ← source */
    private List<RawEdge> queryIncoming(String kbId, Set<String> frontier, int limit) {
        List<RawEdge> result = new ArrayList<>();
        try {
            result = jdbcTemplate.query("""
                            SELECT r.source_entity_id, s.canonical_name AS s_name, s.display_name AS s_display,
                                   s.entity_type AS s_type,
                                   r.target_entity_id, e.canonical_name AS t_name, e.display_name AS t_display,
                                   e.entity_type AS t_type,
                                   r.predicate, r.evidence, r.source_chunk_id, r.weight
                            FROM t_graph_relation r
                            JOIN t_graph_entity s ON s.id = r.source_entity_id
                            JOIN t_graph_entity e ON e.id = r.target_entity_id
                            WHERE r.kb_id = ? AND r.target_entity_id = ANY(?)
                            ORDER BY r.weight DESC
                            LIMIT ?
                            """,
                    (rs, rowNum) -> new RawEdge(
                            rs.getString("source_entity_id"),
                            rs.getString("target_entity_id"),
                            rs.getString("predicate"),
                            rs.getString("evidence"),
                            rs.getString("source_chunk_id"),
                            rs.getDouble("weight"),
                            node(rs.getString("s_name"), rs.getString("s_display"), rs.getString("s_type")),
                            node(rs.getString("t_name"), rs.getString("t_display"), rs.getString("t_type"))),
                    kbId, frontier.toArray(new String[0]), limit);
        } catch (Exception e) {
            log.warn("子图入边查询失败: {}", e.getMessage());
        }
        return result;
    }

    private GraphNode node(String canonical, String display, String type) {
        return new GraphNode(null, canonical == null ? "" : canonical,
                display == null ? "" : display, type == null ? "OTHER" : type, 0, 0);
    }

    private GraphEdge toEdge(RawEdge raw, double score, int depth) {
        return new GraphEdge(raw.sourceId(), raw.source().canonicalName(),
                raw.targetId(), raw.target().canonicalName(),
                raw.predicate(), truncate(raw.evidence(), 200), raw.chunkId(), depth, score);
    }

    private String truncate(String text, int max) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 边查询原始行 */
    private record RawEdge(String sourceId, String targetId, String predicate, String evidence,
                           String chunkId, double weight, GraphNode source, GraphNode target) {
    }
}