package com.byteq.ai.ragstudio.ingestion.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionTaskCreateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionTaskNodeVO;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionTaskVO;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.ingestion.domain.result.IngestionResult;
import com.byteq.ai.ragstudio.ingestion.service.IngestionTaskService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库采集任务控制层
 * <p>
 * 提供数据摄入任务的 RESTful API 接口，包括任务的创建执行、文件上传触发、
 * 任务详情查询、节点运行记录查询以及分页查询功能。
 * </p>
 */
@RestController
@RequiredArgsConstructor
@Validated
public class IngestionTaskController {

    private final IngestionTaskService taskService;

    /**
     * 创建并执行采集任务
     * <p>
     * 根据请求参数创建一个新的数据摄入任务，并立即执行。
     * 请求中需指定使用的流水线 ID 和文档来源信息。
     * </p>
     *
     * @param request 创建任务的请求体，包含流水线 ID 和文档来源等信息
     * @return 任务执行结果，包含任务 ID、状态和处理统计信息
     */
    @PostMapping("/ingestion/tasks")
    public Result<IngestionResult> create(@RequestBody IngestionTaskCreateRequest request) {
        return Results.success(taskService.execute(request));
    }

    /**
     * 上传文件并触发采集任务
     * <p>
     * 通过上传文件的方式触发数据摄入任务，系统会根据文件内容自动进行解析、分块和索引。
     * 请求使用 multipart/form-data 格式上传文件。
     * </p>
     *
     * @param pipelineId 要执行的流水线 ID
     * @param file       上传的文档文件
     * @return 任务执行结果，包含任务 ID、状态和处理统计信息
     */
    @SneakyThrows
    @PostMapping(value = "/ingestion/tasks/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<IngestionResult> upload(@RequestParam(value = "pipelineId") String pipelineId,
                                          @RequestPart("file") MultipartFile file) {
        return Results.success(taskService.upload(pipelineId, file));
    }

    /**
     * 根据任务 ID 获取任务详情
     * <p>
     * 返回任务的详细信息，包括任务状态、使用的流水线、文档来源以及处理结果等。
     * </p>
     *
     * @param id 任务的唯一标识符
     * @return 任务详情视图对象
     */
    @GetMapping("/ingestion/tasks/{id}")
    public Result<IngestionTaskVO> get(@PathVariable String id) {
        return Results.success(taskService.get(id));
    }

    /**
     * 根据任务 ID 获取任务节点运行记录
     * <p>
     * 返回指定任务中各节点的执行记录，包括每个节点的执行耗时、状态和输出信息。
     * 可用于监控和排查管道执行过程中的问题。
     * </p>
     *
     * @param id 任务的唯一标识符
     * @return 节点运行记录列表
     */
    @GetMapping("/ingestion/tasks/{id}/nodes")
    public Result<List<IngestionTaskNodeVO>> nodes(@PathVariable String id) {
        return Results.success(taskService.listNodes(id));
    }

    /**
     * 分页查询采集任务
     * <p>
     * 支持按任务状态进行筛选，返回分页的任务列表。
     * </p>
     *
     * @param pageNo   当前页码，默认值为 1
     * @param pageSize 每页大小，默认值为 10
     * @param status   任务状态筛选（可选），如 pending、running、completed、failed
     * @return 任务分页结果
     */
    @GetMapping("/ingestion/tasks")
    public Result<IPage<IngestionTaskVO>> page(@RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
                                               @RequestParam(value = "pageSize", defaultValue = "10") int pageSize,
                                               @RequestParam(value = "status", required = false) String status) {
        return Results.success(taskService.page(new Page<>(pageNo, pageSize), status));
    }
}
