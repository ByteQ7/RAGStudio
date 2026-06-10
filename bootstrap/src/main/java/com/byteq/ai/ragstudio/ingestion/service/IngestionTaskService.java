package com.byteq.ai.ragstudio.ingestion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionTaskCreateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionTaskNodeVO;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionTaskVO;
import com.byteq.ai.ragstudio.ingestion.domain.result.IngestionResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 数据摄入任务服务接口
 * <p>
 * 定义数据摄入任务的核心业务操作，包括任务的创建执行、文件上传触发、
 * 任务详情查询、节点日志查询以及分页查询功能。
 * 摄入任务通过流水线定义的处理节点链对文档进行获取、解析、分块、增强和索引。
 * </p>
 */
public interface IngestionTaskService {

    /**
     * 执行数据摄入任务
     * <p>
     * 根据请求中的流水线配置和文档来源信息，创建一个新的摄入任务并立即执行。
     * 任务将按照流水线定义的节点顺序依次处理文档。
     * </p>
     *
     * @param request 创建任务的请求体，包含流水线 ID 和文档来源配置
     * @return 摄入任务执行结果，包含任务 ID、状态和处理统计信息
     */
    IngestionResult execute(IngestionTaskCreateRequest request);

    /**
     * 上传文件并执行摄入任务
     * <p>
     * 通过上传文件的方式创建并执行摄入任务。文件内容将通过指定流水线进行解析、分块和索引。
     * 适用于直接上传本地文件的场景。
     * </p>
     *
     * @param pipelineId 要使用的流水线 ID
     * @param file       上传的文档文件
     * @return 摄入任务执行结果，包含任务 ID、状态和处理统计信息
     */
    IngestionResult upload(String pipelineId, MultipartFile file);

    /**
     * 获取任务详情
     * <p>
     * 根据任务 ID 查询任务的详细信息，包括任务状态、使用的流水线、文档来源以及处理结果等。
     * </p>
     *
     * @param taskId 任务的唯一标识符
     * @return 任务详情视图对象，包含完整的任务信息
     */
    IngestionTaskVO get(String taskId);

    /**
     * 分页查询任务
     * <p>
     * 支持按任务状态进行筛选，返回分页的任务列表。
     * </p>
     *
     * @param page   分页参数，包含页码和每页大小
     * @param status 任务状态筛选条件（可选），如 pending、running、completed、failed
     * @return 任务分页结果
     */
    IPage<IngestionTaskVO> page(Page<IngestionTaskVO> page, String status);

    /**
     * 获取任务节点列表
     * <p>
     * 返回指定任务中各节点的执行记录，包括每个节点的执行耗时、状态和输出信息。
     * 用于监控和排查管道执行过程中的问题。
     * </p>
     *
     * @param taskId 任务的唯一标识符
     * @return 任务节点视图对象列表，按执行顺序排列
     */
    List<IngestionTaskNodeVO> listNodes(String taskId);
}
