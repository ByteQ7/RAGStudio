package com.byteq.ai.ragstudio.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 查询词条映射视图对象
 * <p>
 * 用于管理用户查询词条到标准化目标词条的映射关系，
 * 帮助提升检索时匹配的准确性和覆盖率。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueryTermMappingVO {

    /** 映射规则 ID */
    private String id;

    /** 源词条（用户输入的查询词） */
    private String sourceTerm;

    /** 目标词条（映射后的标准化词条） */
    private String targetTerm;

    /** 匹配类型（如精确匹配、模糊匹配等） */
    private Integer matchType;

    /** 优先级，数值越高优先级越高 */
    private Integer priority;

    /** 是否启用 */
    private Boolean enabled;

    /** 备注说明 */
    private String remark;

    /** 关联的知识库 ID 列表 */
    private List<String> knowledgeBaseIds;

    /** 创建时间 */
    private Date createTime;

    /** 最后更新时间 */
    private Date updateTime;
}
