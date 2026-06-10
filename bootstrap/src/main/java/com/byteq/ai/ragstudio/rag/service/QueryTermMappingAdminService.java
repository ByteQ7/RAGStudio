package com.byteq.ai.ragstudio.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingPageRequest;
import com.byteq.ai.ragstudio.rag.controller.request.QueryTermMappingUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.QueryTermMappingVO;

/**
 * 查询词条映射管理服务接口
 * <p>
 * 提供查询词条映射规则的管理功能，包括创建、更新、删除和查询映射规则。
 * 映射规则用于将用户的查询词条映射到标准化的目标词条，提升检索准确性。
 * </p>
 */
public interface QueryTermMappingAdminService {

    /**
     * 创建映射规则
     *
     * @param requestParam 创建映射规则的请求参数，包含源词条、目标词条、匹配类型等信息
     * @return 新创建规则的 ID
     */
    String create(QueryTermMappingCreateRequest requestParam);

    /**
     * 更新映射规则
     *
     * @param id           映射规则 ID
     * @param requestParam 更新映射规则的请求参数
     */
    void update(String id, QueryTermMappingUpdateRequest requestParam);

    /**
     * 删除映射规则
     *
     * @param id 映射规则 ID
     */
    void delete(String id);

    /**
     * 查询映射规则详情
     *
     * @param id 映射规则 ID
     * @return 映射规则视图对象
     */
    QueryTermMappingVO queryById(String id);

    /**
     * 分页查询映射规则
     *
     * @param requestParam 分页查询请求参数，包含分页信息和过滤条件
     * @return 分页的映射规则视图对象列表
     */
    IPage<QueryTermMappingVO> pageQuery(QueryTermMappingPageRequest requestParam);
}
