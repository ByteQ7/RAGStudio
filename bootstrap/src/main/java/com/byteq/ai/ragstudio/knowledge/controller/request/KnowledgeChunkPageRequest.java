package com.byteq.ai.ragstudio.knowledge.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

/**
 * 分片分页查询请求参数
 * <p>继承 MyBatis-Plus Page 分页对象，支持按启用状态筛选分片。</p>
 */
@Data
public class KnowledgeChunkPageRequest extends Page {

    /**
     * 启用状态筛选：null-全部，1-已启用，0-已禁用
     */
    private Integer enabled;

    /**
     * 关键字搜索（按 Chunk ID 模糊匹配）
     */
    private String keyword;
}
