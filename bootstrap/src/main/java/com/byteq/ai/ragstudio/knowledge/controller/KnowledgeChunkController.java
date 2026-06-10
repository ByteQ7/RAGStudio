package com.byteq.ai.ragstudio.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeChunkVO;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库分片（Chunk）管理控制器
 * <p>
 * 提供文档分片（Chunk）的增删改查、启用/禁用、批量操作等管理接口。
 * 分片是知识库中向量检索的最小单元，由文档经过分块策略处理后生成。
 */
@RestController
@RequiredArgsConstructor
@Validated
public class KnowledgeChunkController {

    private final KnowledgeChunkService knowledgeChunkService;

    /**
     * 分页查询分片列表
     * <p>按文档 ID 分页查询该文档下的所有分片，支持按启用状态筛选。</p>
     *
     * @param docId        文档 ID
     * @param requestParam 分页查询参数（包含页码、每页大小、启用状态过滤）
     * @return 包含分片分页结果的响应
     */
    @GetMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<IPage<KnowledgeChunkVO>> pageQuery(@PathVariable("doc-id") String docId,
                                                     @Validated KnowledgeChunkPageRequest requestParam) {
        return Results.success(knowledgeChunkService.pageQuery(docId, requestParam));
    }

    /**
     * 新增分片
     * <p>为指定文档手动新增一条分片记录。</p>
     *
     * @param docId   文档 ID
     * @param request 新增分片请求参数（包含内容、序号等）
     * @return 包含新增分片信息的响应
     */
    @PostMapping("/knowledge-base/docs/{doc-id}/chunks")
    public Result<KnowledgeChunkVO> create(@PathVariable("doc-id") String docId,
                                           @RequestBody KnowledgeChunkCreateRequest request) {
        return Results.success(knowledgeChunkService.create(docId, request));
    }

    /**
     * 更新分片内容
     * <p>更新指定文档下特定分片的正文内容。</p>
     *
     * @param docId   文档 ID
     * @param chunkId 分片 ID
     * @param request 更新请求参数（包含新内容）
     * @return 空成功响应
     */
    @PutMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> update(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId,
                               @RequestBody KnowledgeChunkUpdateRequest request) {
        knowledgeChunkService.update(docId, chunkId, request);
        return Results.success();
    }

    /**
     * 删除分片
     * <p>删除指定文档下的特定分片记录。</p>
     *
     * @param docId   文档 ID
     * @param chunkId 分片 ID
     * @return 空成功响应
     */
    @DeleteMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}")
    public Result<Void> delete(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId) {
        knowledgeChunkService.delete(docId, chunkId);
        return Results.success();
    }

    /**
     * 启用或禁用单条分片
     * <p>启用或禁用指定分片。禁用后该分片在 RAG 向量检索中不再被召回。</p>
     *
     * @param docId   文档 ID
     * @param chunkId 分片 ID
     * @param enabled true-启用，false-禁用
     * @return 空成功响应
     */
    @PatchMapping("/knowledge-base/docs/{doc-id}/chunks/{chunk-id}/enable")
    public Result<Void> enable(@PathVariable("doc-id") String docId,
                               @PathVariable("chunk-id") String chunkId,
                               @RequestParam("value") boolean enabled) {
        knowledgeChunkService.enableChunk(docId, chunkId, enabled);
        return Results.success();
    }

    /**
     * 批量启用或禁用分片
     * <p>批量启用或禁用指定文档下的多个分片。如果请求体中未指定分片 ID 列表，则操作该文档下的所有分片。</p>
     *
     * @param docId   文档 ID
     * @param enabled true-启用，false-禁用
     * @param request 批量操作请求参数（可选，包含要操作的分片 ID 列表）
     * @return 空成功响应
     */
    @PatchMapping("/knowledge-base/docs/{doc-id}/chunks/batch-enable")
    public Result<Void> batchEnable(@PathVariable("doc-id") String docId,
                                    @RequestParam("value") boolean enabled,
                                    @RequestBody(required = false) KnowledgeChunkBatchRequest request) {
        knowledgeChunkService.batchToggleEnabled(docId, request, enabled);
        return Results.success();
    }
}
