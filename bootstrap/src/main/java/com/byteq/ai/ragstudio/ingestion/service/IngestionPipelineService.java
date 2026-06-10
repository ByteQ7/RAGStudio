package com.byteq.ai.ragstudio.ingestion.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.byteq.ai.ragstudio.ingestion.controller.vo.IngestionPipelineVO;
import com.byteq.ai.ragstudio.ingestion.domain.pipeline.PipelineDefinition;

/**
 * 数据摄入流水线服务接口
 * <p>
 * 定义数据摄入流水线的核心业务操作，包括流水线的创建、查询、更新、删除
 * 以及获取流水线完整定义的功能。流水线定义了文档摄入的处理流程，
 * 由多个按顺序执行的处理节点（获取、解析、分块、增强、索引）组成。
 * </p>
 */
public interface IngestionPipelineService {

    /**
     * 创建流水线
     * <p>
     * 根据请求参数创建一条新的数据摄入流水线，包含节点配置和执行顺序。
     * </p>
     *
     * @param request 创建请求体，包含流水线名称、描述及节点配置列表
     * @return 创建成功的流水线视图对象
     */
    IngestionPipelineVO create(IngestionPipelineCreateRequest request);

    /**
     * 更新流水线
     * <p>
     * 更新指定流水线的配置信息，包括名称、描述和节点列表。
     * </p>
     *
     * @param pipelineId 要更新的流水线 ID
     * @param request    更新请求体，包含需要修改的字段
     * @return 更新后的流水线视图对象
     */
    IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request);

    /**
     * 获取流水线详情
     * <p>
     * 根据流水线 ID 查询完整的流水线配置信息。
     * </p>
     *
     * @param pipelineId 流水线的唯一标识符
     * @return 流水线视图对象，包含节点配置列表
     */
    IngestionPipelineVO get(String pipelineId);

    /**
     * 分页查询流水线
     * <p>
     * 支持按关键字模糊搜索流水线名称和描述，返回分页结果。
     * </p>
     *
     * @param page    分页参数，包含页码和每页大小
     * @param keyword 搜索关键字（可选），用于模糊匹配流水线名称
     * @return 流水线分页结果
     */
    IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword);

    /**
     * 删除流水线
     * <p>
     * 根据流水线 ID 删除指定的流水线及其关联的节点配置。
     * </p>
     *
     * @param pipelineId 要删除的流水线 ID
     */
    void delete(String pipelineId);

    /**
     * 获取流水线定义
     * <p>
     * 获取流水线的完整定义信息，包括流水线元数据和所有节点的配置。
     * 该定义用于引擎执行流水线处理。
     * </p>
     *
     * @param pipelineId 流水线的唯一标识符
     * @return 流水线定义对象，包含节点配置列表
     */
    PipelineDefinition getDefinition(String pipelineId);
}
