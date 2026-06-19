package com.byteq.ai.ragstudio.rag.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionCreateRequest;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionPageRequest;
import com.byteq.ai.ragstudio.rag.controller.request.SampleQuestionUpdateRequest;
import com.byteq.ai.ragstudio.rag.controller.vo.SampleQuestionVO;
import com.byteq.ai.ragstudio.rag.dao.entity.SampleQuestionDO;
import com.byteq.ai.ragstudio.rag.dao.mapper.SampleQuestionMapper;
import com.byteq.ai.ragstudio.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 示例问题服务实现类
 * <p>
 * 实现示例问题的 CRUD、分页查询和随机获取功能，用于对话界面推荐问题展示。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SampleQuestionServiceImpl implements SampleQuestionService {

    private static final int DEFAULT_LIMIT = 3;

    private final SampleQuestionMapper sampleQuestionMapper;

    // 校验参数后创建示例问题记录
    @Override
    public String create(SampleQuestionCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String question = StrUtil.trimToNull(requestParam.getQuestion());
        Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));

        SampleQuestionDO record = SampleQuestionDO.builder()
                .title(StrUtil.trimToNull(requestParam.getTitle()))
                .description(StrUtil.trimToNull(requestParam.getDescription()))
                .question(question)
                .build();
        sampleQuestionMapper.insert(record);
        return String.valueOf(record.getId());
    }

    // 加载记录后按非空字段逐个更新
    @Override
    public void update(String id, SampleQuestionUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        SampleQuestionDO record = loadById(id);

        if (requestParam.getQuestion() != null) {
            String question = StrUtil.trimToNull(requestParam.getQuestion());
            Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));
            record.setQuestion(question);
        }
        if (requestParam.getTitle() != null) {
            record.setTitle(StrUtil.trimToNull(requestParam.getTitle()));
        }
        if (requestParam.getDescription() != null) {
            record.setDescription(StrUtil.trimToNull(requestParam.getDescription()));
        }

        sampleQuestionMapper.updateById(record);
    }

    // 删除示例问题
    @Override
    public void delete(String id) {
        SampleQuestionDO record = loadById(id);
        sampleQuestionMapper.deleteById(record.getId());
    }

    // 根据 ID 查询示例问题详情并转换为 VO
    @Override
    public SampleQuestionVO queryById(String id) {
        SampleQuestionDO record = loadById(id);
        return toVO(record);
    }

    // 分页查询示例问题，支持按标题/描述/内容关键字模糊搜索，按更新时间降序
    @Override
    public IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<SampleQuestionDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<SampleQuestionDO> result = sampleQuestionMapper.selectPage(
                page,
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(SampleQuestionDO::getTitle, keyword)
                                .or()
                                .like(SampleQuestionDO::getDescription, keyword)
                                .or()
                                .like(SampleQuestionDO::getQuestion, keyword))
                        .orderByDesc(SampleQuestionDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    // 从启用的示例问题中随机选取指定数量返回，用于前端推荐展示
    @Override
    public List<SampleQuestionVO> listRandomQuestions() {
        List<SampleQuestionDO> records = sampleQuestionMapper.selectList(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .last("ORDER BY RANDOM() LIMIT " + DEFAULT_LIMIT)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toVO)
                .toList();
    }

    // 根据 ID 加载示例问题记录，不存在时抛出异常
    private SampleQuestionDO loadById(String id) {
        SampleQuestionDO record = sampleQuestionMapper.selectOne(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getId, id)
                        .eq(SampleQuestionDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("示例问题不存在"));
        return record;
    }

    // 将示例问题 DO 转换为 VO
    private SampleQuestionVO toVO(SampleQuestionDO record) {
        return SampleQuestionVO.builder()
                .id(String.valueOf(record.getId()))
                .title(record.getTitle())
                .description(record.getDescription())
                .question(record.getQuestion())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}
