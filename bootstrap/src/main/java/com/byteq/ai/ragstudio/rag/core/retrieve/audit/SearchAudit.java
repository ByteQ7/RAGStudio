package com.byteq.ai.ragstudio.rag.core.retrieve.audit;

import com.byteq.ai.ragstudio.framework.convention.RetrievedChunk;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 单次 RAG 检索的审计缓冲。
 * <p>
 * 携带本次检索的 RRF 候选与重排阶段信息，随 {@code SearchContext} 在通道/后置处理器间传递
 * （检索通道运行在异步线程，因此不用 ThreadLocal，改为显式传递），
 * 待 {@code RetrievalEngine} 完成 docName 富化后统一调用 {@link #finish} 输出。
 * </p>
 */
public class SearchAudit {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final SearchAuditRecorder recorder;

    /** 本次检索的唯一标识 */
    private final String searchId;

    private final long startTimeMillis;

    /** RRF 融合阶段的全部候选（含被截断丢弃的） */
    private final CopyOnWriteArrayList<RrfCandidate> candidates = new CopyOnWriteArrayList<>();

    /** 实际生效的重排模型 ID（可能为 null：未配置/自动路由无默认模型） */
    private volatile String rerankModel;

    /** 重排是否降级为原始排序 */
    private volatile boolean rerankFallback;

    SearchAudit(SearchAuditRecorder recorder) {
        this.recorder = recorder;
        this.searchId = UUID.randomUUID().toString();
        this.startTimeMillis = System.currentTimeMillis();
    }

    /** RRF 融合阶段候选 */
    public record RrfCandidate(String chunkId, String collection, float score, int rank) {
    }

    /**
     * 记录一条 RRF 融合候选（chunk 在 RRF 阶段的分数与 per-KB 排名）
     */
    public void addRrfCandidate(String chunkId, String collection, float score, int rank) {
        candidates.add(new RrfCandidate(chunkId, collection, score, rank));
    }

    /**
     * 标记实际生效的重排模型
     */
    public void markRerank(String modelId) {
        this.rerankModel = modelId;
    }

    /**
     * 标记重排失败降级为原始排序
     */
    public void markRerankFallback() {
        this.rerankFallback = true;
    }

    /**
     * 输出本次检索的完整审计记录（由 {@code RetrievalEngine} 在 docName 富化完成后调用）
     *
     * @param query    实际用于检索的查询（改写后主问题）
     * @param userQuery 用户原始提问（未改写）
     * @param kbNames  参与检索的知识库名称
     * @param topK     期望返回数量
     * @param chunks   最终结果（已按重排后顺序排列）
     */
    public void finish(String query, String userQuery, List<String> kbNames, int topK, List<RetrievedChunk> chunks) {
        recorder.write(this, query, userQuery, kbNames, topK, chunks);
    }

    String getSearchId() {
        return searchId;
    }

    String getTime() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    long getLatencyMs() {
        return System.currentTimeMillis() - startTimeMillis;
    }

    String getRerankModel() {
        return rerankModel;
    }

    boolean isRerankFallback() {
        return rerankFallback;
    }

    List<RrfCandidate> getCandidates() {
        return candidates;
    }

    /**
     * 组装最终结果 JSON 数组（每条含文档名/RRF 分/重排顺序/最终分，可选正文）
     */
    List<Map<String, Object>> buildChunkList(List<RetrievedChunk> chunks, Set<String> finalIds) {
        Map<String, RrfCandidate> candById = new LinkedHashMap<>();
        for (RrfCandidate cand : candidates) {
            candById.putIfAbsent(cand.chunkId(), cand);
        }
        List<Map<String, Object>> list = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            RrfCandidate cand = c.getId() != null ? candById.get(c.getId()) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("order", i + 1);
            m.put("chunkId", c.getId());
            m.put("kbName", c.getKbName());
            m.put("docName", c.getDocName());
            m.put("rrfScore", cand != null ? cand.score() : null);
            m.put("rrfRank", cand != null ? cand.rank() : null);
            m.put("rrfCollection", cand != null ? cand.collection() : null);
            m.put("rrfSurvived", cand != null);
            m.put("finalScore", c.getScore());
            if (recorder.isIncludeChunkText() && c.getText() != null) {
                m.put("text", c.getText());
            }
            list.add(m);
        }
        return list;
    }

    /**
     * 组装被截断丢弃的 RRF 候选 JSON 数组（无 docName）
     */
    List<Map<String, Object>> buildCandidateList(Set<String> finalIds) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RrfCandidate cand : candidates) {
            if (cand.chunkId() == null || finalIds.contains(cand.chunkId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chunkId", cand.chunkId());
            m.put("rrfScore", cand.score());
            m.put("rrfRank", cand.rank());
            m.put("rrfCollection", cand.collection());
            m.put("rrfSurvived", false);
            list.add(m);
        }
        return list;
    }

    static Set<String> idSet(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(RetrievedChunk::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    static List<String> dedup(List<String> values) {
        return values.stream().distinct().collect(Collectors.toList());
    }
}
