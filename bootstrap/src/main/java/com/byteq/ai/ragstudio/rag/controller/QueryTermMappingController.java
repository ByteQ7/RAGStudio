package com.byteq.ai.ragstudio.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingPageRequest;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.QueryTermMappingVO;
import com.byteq.ai.ragstudio.rag.service.QueryTermMappingAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关键词映射管理控制器
 * <p>
 * 提供关键词映射规则的增删改查接口，用于管理用户查询词到标准化目标词的映射关系，
 * 帮助提升 RAG 检索时匹配的准确性和覆盖率。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class QueryTermMappingController {

    /**
     * 关键词映射管理服务，提供映射规则的 CRUD 操作
     */
    private final QueryTermMappingAdminService queryTermMappingAdminService;

    /**
     * 分页查询映射规则
     *
     * @param requestParam 分页查询请求，包含关键词过滤和分页参数
     * @return 分页的映射规则列表
     */
    @GetMapping("/mappings")
    public Result<IPage<QueryTermMappingVO>> pageQuery(QueryTermMappingPageRequest requestParam) {
        return Results.success(queryTermMappingAdminService.pageQuery(requestParam));
    }

    /**
     * 查询映射规则详情
     *
     * @param id 映射规则 ID
     * @return 映射规则详情视图对象
     */
    @GetMapping("/mappings/{id}")
    public Result<QueryTermMappingVO> queryById(@PathVariable String id) {
        return Results.success(queryTermMappingAdminService.queryById(id));
    }

    /**
     * 创建映射规则
     *
     * @param requestParam 创建请求，包含源词、目标词、匹配类型等信息
     * @return 新创建的映射规则 ID
     */
    @PostMapping("/mappings")
    public Result<String> create(@RequestBody QueryTermMappingCreateRequest requestParam) {
        return Results.success(queryTermMappingAdminService.create(requestParam));
    }

    /**
     * 更新映射规则
     *
     * @param id           映射规则 ID
     * @param requestParam 更新请求，包含需要修改的映射规则字段
     * @return 操作结果
     */
    @PutMapping("/mappings/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody QueryTermMappingUpdateRequest requestParam) {
        queryTermMappingAdminService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除映射规则
     *
     * @param id 映射规则 ID
     * @return 操作结果
     */
    @DeleteMapping("/mappings/{id}")
    public Result<Void> delete(@PathVariable String id) {
        queryTermMappingAdminService.delete(id);
        return Results.success();
    }
}
