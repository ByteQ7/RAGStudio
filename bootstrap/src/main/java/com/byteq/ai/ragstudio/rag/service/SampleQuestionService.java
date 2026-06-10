package com.byteq.ai.ragstudio.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionPageRequest;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.SampleQuestionVO;

import java.util.List;

/**
 * 示例问题服务接口
 * <p>
 * 提供示例问题的管理功能，包括创建、更新、删除、查询和随机获取等操作。
 * 示例问题用于在对话界面向用户展示推荐问题列表，帮助用户快速了解系统能力。
 * </p>
 */
public interface SampleQuestionService {

    /**
     * 创建示例问题
     *
     * @param requestParam 创建示例问题的请求参数，包含标题、描述和问题内容
     * @return 新创建示例问题的 ID
     */
    String create(SampleQuestionCreateRequest requestParam);

    /**
     * 更新示例问题
     *
     * @param id           示例问题 ID
     * @param requestParam 更新示例问题的请求参数
     */
    void update(String id, SampleQuestionUpdateRequest requestParam);

    /**
     * 删除示例问题
     *
     * @param id 示例问题 ID
     */
    void delete(String id);

    /**
     * 查询示例问题详情
     *
     * @param id 示例问题 ID
     * @return 示例问题视图对象
     */
    SampleQuestionVO queryById(String id);

    /**
     * 分页查询示例问题列表
     *
     * @param requestParam 分页查询请求参数，包含分页信息和过滤条件
     * @return 分页的示例问题视图对象列表
     */
    IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam);

    /**
     * 随机获取示例问题列表
     * <p>
     * 从所有启用的示例问题中随机选取一批返回，用于在对话界面展示推荐问题。
     * </p>
     *
     * @return 随机选取的示例问题视图对象列表
     */
    List<SampleQuestionVO> listRandomQuestions();
}
