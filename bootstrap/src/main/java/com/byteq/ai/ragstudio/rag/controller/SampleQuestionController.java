package com.byteq.ai.ragstudio.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.byteq.ai.ragstudio.framework.convention.Result;
import com.byteq.ai.ragstudio.framework.web.Results;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionPageRequest;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.SampleQuestionVO;
import com.byteq.ai.ragstudio.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 示例问题控制器
 * <p>
 * 提供示例问题（欢迎页展示）的管理接口，包括随机获取、分页查询、创建、更新和删除等操作。
 * 示例问题用于对话界面欢迎页向用户推荐常见问题，引导用户快速开始对话。
 * </p>
 */
@RestController
@RequiredArgsConstructor
public class SampleQuestionController {

    /**
     * 示例问题服务，提供示例问题的 CRUD 和随机查询操作
     */
    private final SampleQuestionService sampleQuestionService;

    /**
     * 随机获取示例问题列表
     *
     * @return 随机选取的示例问题列表，用于欢迎页展示
     */
    @GetMapping("/rag/sample-questions")
    public Result<List<SampleQuestionVO>> listSampleQuestions() {
        return Results.success(sampleQuestionService.listRandomQuestions());
    }

    /**
     * 分页查询示例问题列表
     *
     * @param requestParam 分页查询请求，包含关键词过滤和分页参数
     * @return 分页的示例问题列表
     */
    @GetMapping("/sample-questions")
    public Result<IPage<SampleQuestionVO>> pageQuery(SampleQuestionPageRequest requestParam) {
        return Results.success(sampleQuestionService.pageQuery(requestParam));
    }

    /**
     * 查询示例问题详情
     *
     * @param id 示例问题 ID
     * @return 示例问题详情视图对象
     */
    @GetMapping("/sample-questions/{id}")
    public Result<SampleQuestionVO> queryById(@PathVariable String id) {
        return Results.success(sampleQuestionService.queryById(id));
    }

    /**
     * 创建示例问题
     *
     * @param requestParam 创建请求，包含标题、描述和问题内容
     * @return 新创建的示例问题 ID
     */
    @PostMapping("/sample-questions")
    public Result<String> create(@RequestBody SampleQuestionCreateRequest requestParam) {
        return Results.success(sampleQuestionService.create(requestParam));
    }

    /**
     * 更新示例问题
     *
     * @param id           示例问题 ID
     * @param requestParam 更新请求，包含需要修改的示例问题字段
     * @return 操作结果
     */
    @PutMapping("/sample-questions/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody SampleQuestionUpdateRequest requestParam) {
        sampleQuestionService.update(id, requestParam);
        return Results.success();
    }

    /**
     * 删除示例问题
     *
     * @param id 示例问题 ID
     * @return 操作结果
     */
    @DeleteMapping("/sample-questions/{id}")
    public Result<Void> delete(@PathVariable String id) {
        sampleQuestionService.delete(id);
        return Results.success();
    }
}
