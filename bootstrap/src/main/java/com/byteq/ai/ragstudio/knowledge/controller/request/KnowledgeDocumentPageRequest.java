package com.byteq.ai.ragstudio.knowledge.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

/**
 * 文档分页查询请求参数
 * <p>继承 MyBatis-Plus Page 分页对象，支持按文档状态和关键词筛选。</p>
 */
@Data
public class KnowledgeDocumentPageRequest extends Page {

    /**
     * 文档状态筛选：pending / running / success / failed
     */
    private String status;

    /**
     * 搜索关键词（用于文档名称模糊匹配）
     */
    private String keyword;
}
