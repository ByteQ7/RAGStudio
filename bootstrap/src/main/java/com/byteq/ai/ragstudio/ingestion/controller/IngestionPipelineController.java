package com.byteq.ai.ragstudio.ingestion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionPipelineVO;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据摄入流水线控制层
 * <p>
 * 提供数据摄入流水线的 RESTful API 接口，包括流水线的创建、查询、更新和删除操作。
 * 流水线定义了文档摄入的处理流程，由多个处理节点按顺序组成。
 * </p>
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionPipelineController {

    private final IngestionPipelineService pipelineService;

    /**
     * 创建数据摄入流水线
     * <p>
     * 根据请求参数创建一个新的数据摄入流水线，包含节点配置和执行顺序定义。
     * </p>
     *
     * @param request 创建流水线的请求体，包含流水线名称、描述及节点配置
     * @return 创建成功的流水线视图对象
     */
    @PostMapping("/ingestion/pipelines")
    public Result<IngestionPipelineVO> create(@RequestBody IngestionPipelineCreateRequest request) {
        return Results.success(pipelineService.create(request));
    }

    /**
     * 更新数据摄入流水线
     * <p>
     * 更新指定流水线的配置信息，包括节点列表和执行参数。
     * </p>
     *
     * @param id      流水线的唯一标识符
     * @param request 更新流水线的请求体，包含需要修改的字段
     * @return 更新后的流水线视图对象
     */
    @PutMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> update(@PathVariable String id,
                                              @RequestBody IngestionPipelineUpdateRequest request) {
        return Results.success(pipelineService.update(id, request));
    }

    /**
     * 获取单个数据摄入流水线详情
     * <p>
     * 根据流水线 ID 获取完整的流水线配置信息，包括节点列表和执行参数。
     * </p>
     *
     * @param id 流水线的唯一标识符
     * @return 流水线视图对象
     */
    @GetMapping("/ingestion/pipelines/{id}")
    public Result<IngestionPipelineVO> get(@PathVariable String id) {
        return Results.success(pipelineService.get(id));
    }

    /**
     * 分页查询数据摄入流水线
     * <p>
     * 支持按关键字模糊搜索流水线名称和描述，返回分页结果。
     * </p>
     *
     * @param pageNo   当前页码，默认值为 1
     * @param pageSize 每页大小，默认值为 10
     * @param keyword  搜索关键字（可选），用于模糊匹配流水线名称
     * @return 流水线分页结果
     */
    @GetMapping("/ingestion/pipelines")
    public Result<IPage<IngestionPipelineVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                                   @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                                   @RequestParam(value = "keyword", required = false) String keyword) {
        return Results.success(pipelineService.page(new Page<>(pageNo, pageSize), keyword));
    }

    /**
     * 删除数据摄入流水线
     * <p>
     * 根据流水线 ID 删除指定的流水线配置及其关联的节点配置。
     * </p>
     *
     * @param id 要删除的流水线唯一标识符
     * @return 操作成功的响应
     */
    @DeleteMapping("/ingestion/pipelines/{id}")
    public Result<Void> delete(@PathVariable String id) {
        pipelineService.delete(id);
        return Results.success();
    }
}
