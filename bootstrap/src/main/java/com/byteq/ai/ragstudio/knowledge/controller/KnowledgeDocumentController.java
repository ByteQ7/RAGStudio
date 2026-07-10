package com.byteq.ai.ragstudio.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeDocumentUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentChunkLogVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeDocumentSearchVO;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档管理控制器
 * <p>
 * 提供知识库中文档的上传、分块处理、删除、查询、启用/禁用以及全局检索等完整生命周期管理功能。
 * 文档来源支持本地文件上传和远程 URL 获取两种方式，上传后可异步执行文本提取、分块和向量嵌入。
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    /**
     * 上传文档
     * <p>将文档记录写入数据库并将文件存储至文件系统或 OSS，返回文档的视图对象。</p>
     *
     * @param kbId         所属知识库 ID
     * @param file         上传的 Multipart 文件（sourceType=file 时必传）
     * @param requestParam 上传请求参数（包含来源类型、来源 URL、处理模式、分块策略等）
     * @return 包含文档详细信息的成功响应
     */
    @PostMapping(value = "/knowledge-base/{kb-id}/docs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentVO> upload(@PathVariable("kb-id") String kbId,
                                              @RequestPart(value = "file", required = false) MultipartFile file,
                                              @ModelAttribute KnowledgeDocumentUploadRequest requestParam) {
        return Results.success(documentService.upload(kbId, requestParam, file));
    }

    /**
     * 开始分块
     * <p>校验文档状态后发送 MQ 消息到 RocketMQ 进行异步分块处理，立即返回。
     * 异步流程包括：文本提取、文本分块、向量嵌入并写入向量库。</p>
     *
     * @param docId 文档 ID
     * @return 空成功响应
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunk")
    public Result<Void> startChunk(@PathVariable(value = "doc-id") String docId) {
        documentService.startChunk(docId);
        return Results.success();
    }

    /**
     * 批量分块
     * <p>
     * 批量触发指定文档的分块处理，每个文档通过 MQ 消息异步执行。
     * </p>
     *
     * @param docIds 文档 ID 列表
     * @return 操作结果
     */
    @PostMapping("/knowledge-base/docs/batch-chunk")
    public Result<Void> startBatchChunk(@RequestBody List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Results.success();
        }
        for (String docId : docIds) {
            documentService.startChunk(docId);
        }
        return Results.success();
    }

    /**
     * 删除文档
     * <p>逻辑删除文档记录，并可选删除该文档关联的所有分块记录和向量库中的向量数据。</p>
     *
     * @param docId 文档 ID
     * @return 空成功响应
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}")
    public Result<Void> delete(@PathVariable(value = "doc-id") String docId) {
        documentService.delete(docId);
        return Results.success();
    }

    /**
     * 查询文档详情
     *
     * @param docId 文档 ID
     * @return 包含文档详细信息的成功响应
     */
    @GetMapping("/knowledge-base/docs/{docId}")
    public Result<KnowledgeDocumentVO> get(@PathVariable String docId) {
        return Results.success(documentService.get(docId));
    }

    /**
     * 更新文档信息
     * <p>更新文档的名称、处理模式、分块策略、定时同步配置等信息。</p>
     *
     * @param docId        文档 ID
     * @param requestParam 更新请求参数
     * @return 空成功响应
     */
    @PutMapping("/knowledge-base/docs/{docId}")
    public Result<Void> update(@PathVariable String docId,
                               @RequestBody KnowledgeDocumentUpdateRequest requestParam) {
        documentService.update(docId, requestParam);
        return Results.success();
    }

    /**
     * 分页查询文档列表
     * <p>支持按文档状态和关键词进行筛选过滤，以分页方式返回指定知识库下的文档列表。</p>
     *
     * @param kbId         知识库 ID
     * @param requestParam 分页查询参数（包含页码、每页大小、状态筛选、关键词搜索）
     * @return 包含文档分页结果的成功响应
     */
    @GetMapping("/knowledge-base/{kb-id}/docs")
    public Result<IPage<KnowledgeDocumentVO>> page(@PathVariable(value = "kb-id") String kbId,
                                                   KnowledgeDocumentPageRequest requestParam) {
        return Results.success(documentService.page(kbId, requestParam));
    }

    /**
     * 搜索文档（全局检索建议）
     * <p>根据关键词搜索所有知识库下的文档名称，返回匹配的文档列表用于前端检索建议。</p>
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回数量（默认 8）
     * @return 包含文档搜索结果的列表响应
     */
    @GetMapping("/knowledge-base/docs/search")
    public Result<List<KnowledgeDocumentSearchVO>> search(@RequestParam(value = "keyword", required = false) String keyword,
                                                          @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return Results.success(documentService.search(keyword, limit));
    }

    /**
     * 启用/禁用文档
     * <p>启用或禁用指定文档。禁用后该文档在 RAG 检索中不再被召回。</p>
     *
     * @param docId   文档 ID
     * @param enabled true-启用，false-禁用
     * @return 空成功响应
     */
    @PatchMapping("/knowledge-base/docs/{docId}/enable")
    public Result<Void> enable(@PathVariable String docId,
                               @RequestParam("value") boolean enabled) {
        documentService.enable(docId, enabled);
        return Results.success();
    }

    /**
     * 查询文档分块日志列表
     * <p>返回指定文档的历史分块处理日志，包含各阶段的耗时、分块数量和错误信息。</p>
     *
     * @param docId 文档 ID
     * @param page  分页参数
     * @return 包含分块日志分页结果的响应
     */
    @GetMapping("/knowledge-base/docs/{docId}/chunk-logs")
    public Result<IPage<KnowledgeDocumentChunkLogVO>> getChunkLogs(@PathVariable String docId,
                                                                   Page<KnowledgeDocumentChunkLogVO> page) {
        return Results.success(documentService.getChunkLogs(docId, page));
    }
}
