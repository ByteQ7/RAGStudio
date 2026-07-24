package com.byteq.ai.ragstudio.rag.core.vector;

import com.byteq.ai.ragstudio.core.chunk.VectorChunk;

import java.util.List;

/**
 * 向量存储服务接口
 * <p>
 * 向量按维度分表存储，表名为 t_knowledge_vector_{dimension}。
 * 所有操作都需要传入 dimension 参数以确定目标表。
 * </p>
 */
public interface VectorStoreService {

    /**
     * 批量建立文档的向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param dimension      向量维度（如 1024、1536，≤ 2000）
     * @param chunks         文档切片列表，须包含已计算好的 embedding
     */
    void indexDocumentChunks(String collectionName, String docId, int dimension, List<VectorChunk> chunks);

    /**
     * 更新单个 chunk 的向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param chunk          待更新的文档切片，须包含最新的 embedding
     */
    void updateChunk(String collectionName, String docId, VectorChunk chunk);

    /**
     * 删除文档的所有向量索引
     *
     * @param collectionName 向量空间名称（知识库 collectionName）
     * @param docId          文档唯一标识
     * @param dimension      向量维度
     */
    void deleteDocumentVectors(String collectionName, String docId, int dimension);

    /**
     * 删除指定的单个 chunk 向量索引
     *
     * @param dimension      向量维度
     * @param chunkId        chunk 的唯一标识
     */
    void deleteChunkById(int dimension, String chunkId);

    /**
     * 批量删除指定 chunk 的向量索引
     *
     * @param dimension      向量维度
     * @param chunkIds       chunk 唯一标识列表
     */
    void deleteChunksByIds(int dimension, List<String> chunkIds);

    /**
     * 根据维度获取向量表名
     */
    default String vectorTableName(int dimension) {
        return "t_knowledge_vector_" + dimension;
    }
}
