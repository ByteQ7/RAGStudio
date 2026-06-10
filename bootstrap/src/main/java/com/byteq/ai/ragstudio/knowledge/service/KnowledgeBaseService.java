package com.byteq.ai.ragstudio.knowledge.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBasePageRequest;
import com.byteq.ai.ragstudio.knowledge.controller.request.KnowledgeBaseUpdateRequest;
import com.byteq.ai.ragstudio.knowledge.controller.vo.KnowledgeBaseVO;

/**
 * 知识库服务接口
 * <p>
 * 定义知识库的完整生命周期管理操作，包括创建、更新、重命名、删除以及查询功能。
 * 知识库是文档和分片的顶层容器，一个知识库对应一个向量集合用于向量存储。
 */
public interface KnowledgeBaseService {

    /**
     * 创建知识库
     * <p>根据请求参数创建新的知识库，包含嵌入模型配置和向量集合名称。</p>
     *
     * @param requestParam 创建知识库请求参数（包含名称、嵌入模型、Collection 名称）
     * @return 知识库 ID
     */
    String create(KnowledgeBaseCreateRequest requestParam);

    /**
     * 更新知识库
     * <p>更新知识库的基本信息（名称、嵌入模型配置等）。</p>
     *
     * @param requestParam 更新知识库请求参数
     */
    void update(KnowledgeBaseUpdateRequest requestParam);

    /**
     * 重命名知识库
     * <p>根据知识库 ID 修改知识库的名称和嵌入模型配置。</p>
     *
     * @param kbId         知识库 ID
     * @param requestParam 重命名请求参数（包含新名称等）
     */
    void rename(String kbId, KnowledgeBaseUpdateRequest requestParam);

    /**
     * 删除知识库
     * <p>逻辑删除指定知识库，标记 deleted=1。</p>
     *
     * @param kbId 知识库 ID
     */
    void delete(String kbId);

    /**
     * 根据 ID 查询知识库详情
     * <p>返回知识库的详细信息，包括文档数量统计。</p>
     *
     * @param kbId 知识库 ID
     * @return 知识库详细信息视图对象
     */
    KnowledgeBaseVO queryById(String kbId);

    /**
     * 分页查询知识库列表
     * <p>支持按名称模糊搜索，以分页方式返回知识库列表。</p>
     *
     * @param requestParam 分页查询请求参数（包含页码、每页大小、名称筛选条件）
     * @return 知识库分页结果
     */
    IPage<KnowledgeBaseVO> pageQuery(KnowledgeBasePageRequest requestParam);
}
