package com.byteq.ai.ragstudio.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.core.chunk.ChunkingMode;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBasePageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.ChunkStrategyVO;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeBaseVO;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 知识库控制器
 * <p>
 * 提供知识库的创建、查询、修改、删除等基础管理操作接口，
 * 以及查询系统支持的分块策略列表功能。
 * 所有接口统一返回 {@link Result} 格式的响应体。
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建知识库
     * <p>接收知识库名称、嵌入模型和向量集合名称，创建新的知识库并返回知识库 ID。</p>
     *
     * @param requestParam 创建知识库请求参数
     * @return 包含知识库 ID 的成功响应
     */
    @PostMapping("/knowledge-base")
    public Result<String> createKnowledgeBase(@RequestBody KnowledgeBaseCreateRequest requestParam) {
        return Results.success(knowledgeBaseService.create(requestParam));
    }

    /**
     * 重命名知识库
     * <p>根据知识库 ID 修改知识库的名称和嵌入模型配置。</p>
     *
     * @param kbId         知识库 ID
     * @param requestParam 重命名请求参数（包含新名称等）
     * @return 空成功响应
     */
    @PutMapping("/knowledge-base/{kb-id}")
    public Result<Void> renameKnowledgeBase(@PathVariable("kb-id") String kbId,
                                            @RequestBody KnowledgeBaseUpdateRequest requestParam) {
        knowledgeBaseService.rename(kbId, requestParam);
        return Results.success();
    }

    /**
     * 删除知识库
     * <p>根据知识库 ID 逻辑删除指定的知识库。</p>
     *
     * @param kbId 知识库 ID
     * @return 空成功响应
     */
    @DeleteMapping("/knowledge-base/{kb-id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable("kb-id") String kbId) {
        knowledgeBaseService.delete(kbId);
        return Results.success();
    }

    /**
     * 查询知识库详情
     * <p>根据知识库 ID 查询知识库的详细信息，包括名称、嵌入模型、Collection 名称、文档数量等。</p>
     *
     * @param kbId 知识库 ID
     * @return 包含知识库详情的成功响应
     */
    @GetMapping("/knowledge-base/{kb-id}")
    public Result<KnowledgeBaseVO> queryKnowledgeBase(@PathVariable("kb-id") String kbId) {
        return Results.success(knowledgeBaseService.queryById(kbId));
    }

    /**
     * 分页查询知识库列表
     * <p>支持按知识库名称模糊搜索，以分页方式返回知识库列表。</p>
     *
     * @param requestParam 分页查询参数（包含页码、每页大小、名称筛选条件）
     * @return 包含知识库分页结果的成功响应
     */
    @GetMapping("/knowledge-base")
    public Result<IPage<KnowledgeBaseVO>> pageQuery(KnowledgeBasePageRequest requestParam) {
        return Results.success(knowledgeBaseService.pageQuery(requestParam));
    }

    /**
     * 查询支持的分块策略列表
     * <p>返回系统中所有可见的分块策略，包含策略值、显示名称和默认配置参数。</p>
     *
     * @return 包含分块策略列表的成功响应
     */
    @GetMapping("/knowledge-base/chunk-strategies")
    public Result<List<ChunkStrategyVO>> listChunkStrategies() {
        List<ChunkStrategyVO> list = Arrays.stream(ChunkingMode.values())
                .filter(ChunkingMode::isVisible)
                .map(mode -> new ChunkStrategyVO(mode.getValue(), mode.getLabel(), mode.getDefaultConfig()))
                .toList();
        return Results.success(list);
    }
}
