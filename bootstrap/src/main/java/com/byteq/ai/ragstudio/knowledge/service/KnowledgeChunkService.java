package com.byteq.ai.ragstudio.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeChunkVO;

import java.util.List;

/**
 * 知识库分片（Chunk）服务接口
 * <p>
 * 定义文档分片（Chunk）的完整管理操作，包括分页查询、新增、批量新增、更新、删除以及启用/禁用功能。
 * 分片是知识库中向量检索的最小单元，由文档经过分块策略处理后生成，包含文本内容和向量表示。
 */
public interface KnowledgeChunkService {

    /**
     * 全局按 Chunk ID 搜索分片
     * <p>跨所有知识库和文档，按 Chunk ID 模糊搜索。返回结果中填充 kbName、docName 字段。</p>
     *
     * @param keyword 搜索关键词（Chunk ID 模糊匹配）
     * @param current 页码
     * @param size    每页条数
     * @return 分片分页信息（含所属知识库名称和文档名称）
     */
    IPage<KnowledgeChunkVO> searchChunksGlobally(String keyword, int current, int size);

    /**
     * 分页查询指定文档的分片列表
     * <p>按文档 ID 分页查询分片列表，支持按启用状态筛选过滤。</p>
     *
     * @param docId        文档 ID
     * @param requestParam 分页查询参数（包含页码、每页大小、启用状态过滤）
     * @return 分片分页信息
     */
    IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam);

    /**
     * 为指定文档新增分片
     *
     * @param docId        文档 ID
     * @param requestParam 新增分片请求参数（包含内容和序号）
     * @return 新增的分片视图对象
     */
    KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam);

    /**
     * 批量新增文档分片（默认不写入向量库）
     * <p>批量创建分片记录，适合在文档分块处理时批量写入。默认不写入向量库，需要额外调用向量化。</p>
     *
     * @param docId         文档 ID
     * @param requestParams 批量新增分片请求参数列表
     */
    void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams);

    /**
     * 批量新增文档分片（可选同步写入向量库）
     * <p>批量创建分片记录，可根据 writeVector 参数决定是否同步将分片向量写入向量库。</p>
     *
     * @param docId         文档 ID
     * @param requestParams 批量新增分片请求参数列表
     * @param writeVector   是否同步写入向量库
     */
    void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams, boolean writeVector);

    /**
     * 更新指定文档的特定分片内容
     * <p>更新分片的文本内容，并重新生成内容哈希。</p>
     *
     * @param docId        文档 ID
     * @param chunkId      分片 ID
     * @param requestParam 更新分片请求参数（包含新内容）
     */
    void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam);

    /**
     * 删除指定文档的特定分片
     * <p>逻辑删除指定分片记录。</p>
     *
     * @param docId   文档 ID
     * @param chunkId 分片 ID
     */
    void delete(String docId, String chunkId);

    /**
     * 启用或禁用单个分片
     * <p>启用或禁用指定分片。禁用后该分片在 RAG 向量检索中不再被召回。</p>
     *
     * @param docId   文档 ID
     * @param chunkId 分片 ID
     * @param enabled true-启用，false-禁用
     */
    void enableChunk(String docId, String chunkId, boolean enabled);

    /**
     * 批量启用或禁用文档分片
     * <p>批量操作指定文档下的多个分片。如果 requestParam 为空或 chunkIds 为空列表，则操作该文档下的所有分片。</p>
     *
     * @param docId        文档 ID
     * @param requestParam 批量处理请求参数（可选，包含要操作的分片 ID 列表）
     * @param enabled      true-启用，false-禁用
     */
    void batchToggleEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled);

    /**
     * 根据文档 ID 批量更新所有分片的启用状态
     * <p>当文档启用/禁用时同步更新该文档下所有分片的启用状态。</p>
     *
     * @param docId   文档 ID
     * @param kbId    知识库 ID（用于操作日志记录，避免重复查询）
     * @param enabled true-启用，false-禁用
     */
    void updateEnabledByDocId(String docId, String kbId, boolean enabled);

    /**
     * 根据文档 ID 查询所有分片列表
     *
     * @param docId 文档 ID
     * @return 分片列表
     */
    List<KnowledgeChunkVO> listByDocId(String docId);

    /**
     * 删除指定文档的所有分片
     * <p>根据文档 ID 逻辑删除该文档下的所有分片记录，通常用于文档重新分块前的清理。</p>
     *
     * @param docId 文档 ID
     */
    void deleteByDocId(String docId);
}
