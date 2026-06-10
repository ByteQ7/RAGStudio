package com.byteq.ai.ragstudio.knowledge.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

/**
 * 知识库分页查询请求参数
 * <p>继承 MyBatis-Plus Page 分页对象，支持按知识库名称模糊搜索。</p>
 */
@Data
public class KnowledgeBasePageRequest extends Page {

    /**
     * 知识库名称（支持模糊匹配）
     */
    private String name;
}
