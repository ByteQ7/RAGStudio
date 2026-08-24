package com.byteq.ai.ragstudio.graph.service;

import com.byteq.ai.ragstudio.core.chunk.VectorChunk;
import com.byteq.ai.ragstudio.graph.extract.GraphExtractionResult;

import java.util.List;

/**
 * 图谱抽取服务
 * <p>负责实体-关系图谱的构建与维护：
 * <ul>
 *   <li>文档级增量抽取（chunk 内容哈希缓存，未变更 chunk 零 LLM 成本）</li>
 *   <li>文档/单 chunk 删除时级联清理图谱数据并清理孤立实体</li>
 *   <li>知识库全量重建（复用缓存，幂等）</li>
 * </ul>
 * 所有方法内部均对总开关（后管「知识图谱」页配置的 t_graph_config.enabled）做短路，关闭时静默跳过。</p>
 */
public interface GraphExtractionService {

    /**
     * 图谱总开关是否开启
     */
    boolean isEnabled();

    /**
     * 指定知识库是否已构建图谱（存在关系数据即视为已构建）
     */
    boolean isKbGraphBuilt(String kbId);

    /**
     * 文档级增量抽取（同步）：仅对内容变更的 chunk 调用 LLM，其余复用缓存。
     * 对文档的所有 chunk 重新建立关系（幂等），并清理被移除 chunk 的图数据。
     *
     * @param kbId 知识库 ID
     * @param docId 文档 ID
     * @param chunks 当前文档的全部 chunk（含 chunkId 与内容）
     * @param triggerType 触发类型（DOC/KB/CHUNK）
     * @return 构建统计报告（空报告表示跳过）
     */
    GraphExtractionReport extractForDocument(String kbId, String docId, List<VectorChunk> chunks, String triggerType);

    /**
     * 文档级增量抽取（异步）：供分块任务完成后触发，不阻塞分块链路
     */
    void extractForDocumentAsync(String kbId, String docId, List<VectorChunk> chunks);

    /**
     * 单 chunk 内容变更后的增量抽取：加载文档其余 chunk 复用缓存，仅重抽变更 chunk
     *
     * @param kbId 知识库 ID
     * @param docId 文档 ID
     * @param chunkId 变更的 chunk ID
     * @param content 变更后的内容
     */
    void extractForChunk(String kbId, String docId, String chunkId, String content);

    /**
     * 删除文档的图谱数据（关系 + 抽取缓存 + 孤立实体清理）
     */
    void deleteDocumentGraph(String kbId, String docId);

    /**
     * 删除单 chunk 的图谱数据（关系 + 抽取缓存 + 孤立实体清理）
     */
    void deleteChunkGraph(String kbId, String docId, String chunkId);

    /**
     * 知识库全量重建（异步任务，复用缓存；返回任务描述）
     */
    String rebuildKnowledgeBase(String kbId);

    /**
     * 抽取统计报告
     *
     * @param entityAdded 新增实体数
     * @param entityMerged 复用/合并实体数
     * @param relationAdded 新增关系数
     * @param relationRemoved 删除关系数
     * @param llmCalls LLM 调用次数
     * @param cachedChunks 复用缓存的 chunk 数
     * @param failedChunks 抽取失败的 chunk 数
     * @param durationMs 总耗时（毫秒）
     */
    record GraphExtractionReport(int entityAdded, int entityMerged, int relationAdded, int relationRemoved,
                                 int llmCalls, int cachedChunks, int failedChunks, long durationMs) {

        public static GraphExtractionReport empty() {
            return new GraphExtractionReport(0, 0, 0, 0, 0, 0, 0, 0);
        }

        public boolean skipped() {
            return durationMs == 0 && entityAdded == 0 && relationAdded == 0 && llmCalls == 0;
        }
    }
}