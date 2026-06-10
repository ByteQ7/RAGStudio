package com.byteq.ai.ragstudio.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档服务接口
 * <p>
 * 定义知识库中文档的完整生命周期管理操作，包括上传、分块处理、删除、查询、启用/禁用以及全局检索功能。
 * 文档支持本地文件上传和远程 URL 导入两种来源，上传后支持异步分块和向量化处理流程。
 */
public interface KnowledgeDocumentService {

    /**
     * 上传文档
     * <p>将文档记录写入数据库并将文件存储至文件系统或 OSS，返回文档的视图对象。</p>
     *
     * @param kbId         所属知识库 ID
     * @param requestParam 上传请求参数（包含来源类型、处理模式、分块策略等）
     * @param file         待上传的 Multipart 文件（sourceType=file 时必传）
     * @return 文档视图对象
     */
    KnowledgeDocumentVO upload(String kbId, KnowledgeDocumentUploadRequest requestParam, MultipartFile file);

    /**
     * 开始文档分片处理
     * <p>校验文档状态后发送 MQ 消息到 RocketMQ 进行异步分块处理，该方法立即返回不阻塞。</p>
     *
     * @param docId 文档 ID
     */
    void startChunk(String docId);

    /**
     * 执行文档分块（由 MQ 消费者异步调用）
     * <p>完整的异步分块处理流程：获取分布式锁 → 抽取文本内容 → 按策略切分文本 →
     * 向量嵌入 → 写入向量库及关系数据库 → 清理历史分块和向量。</p>
     *
     * @param docId 文档 ID
     */
    void executeChunk(String docId);

    /**
     * 删除文档
     * <p>逻辑删除文档记录，同时清理该文档关联的所有分块记录和向量库中的向量数据。</p>
     *
     * @param docId 文档 ID
     */
    void delete(String docId);

    /**
     * 获取文档详情
     *
     * @param docId 文档 ID
     * @return 文档视图对象
     */
    KnowledgeDocumentVO get(String docId);

    /**
     * 更新文档信息
     * <p>更新文档的名称、处理模式、分块策略、定时同步配置等信息。</p>
     *
     * @param docId        文档 ID
     * @param requestParam 更新请求参数
     */
    void update(String docId, KnowledgeDocumentUpdateRequest requestParam);

    /**
     * 分页查询文档列表
     * <p>按知识库 ID 分页查询文档列表，支持按文档状态和关键词进行筛选过滤。</p>
     *
     * @param kbId         知识库 ID
     * @param requestParam 分页查询参数（包含页码、每页大小、状态筛选、关键词搜索）
     * @return 文档分页结果
     */
    IPage<KnowledgeDocumentVO> page(String kbId, KnowledgeDocumentPageRequest requestParam);

    /**
     * 启用或禁用文档
     * <p>启用或禁用指定文档。禁用后该文档在 RAG 检索中不再被召回。</p>
     *
     * @param docId   文档 ID
     * @param enabled true-启用，false-禁用
     */
    void enable(String docId, boolean enabled);

    /**
     * 搜索文档（用于全局检索建议）
     * <p>根据关键词搜索所有知识库下的文档名称，用于前端检索建议功能。</p>
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回数量
     * @return 匹配的文档列表
     */
    List<KnowledgeDocumentSearchVO> search(String keyword, int limit);

    /**
     * 查询文档分块日志
     * <p>返回指定文档的历史分块处理日志，包含各阶段耗时、分块数量和错误信息。</p>
     *
     * @param docId 文档 ID
     * @param page  分页参数
     * @return 分块日志分页结果
     */
    IPage<KnowledgeDocumentChunkLogVO> getChunkLogs(String docId, Page<KnowledgeDocumentChunkLogVO> page);
}
