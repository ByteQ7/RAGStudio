package com.byteq.ai.ragstudio.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.byteq.ai.ragstudio.knowledge.dao.handler.JsonbTypeHandler;
import lombok.Data;

import java.util.Date;

/**
 * 查询术语映射实体类
 * 用于存储用户原始短语与归一化目标短语之间的映射关系，支持多种匹配类型和优先级配置
 */
@Data
@TableName(value = "t_query_term_mapping", autoResultMap = true)
public class QueryTermMappingDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 业务域/系统标识，如biz、group、data_security等，可选
     */
    private String domain;

    /**
     * 用户原始短语
     */
    private String sourceTerm;

    /**
     * 归一化后的目标短语
     */
    private String targetTerm;

    /**
     * 匹配类型 1：精确匹配 2：前缀匹配 3：正则匹配 4：整词匹配
     */
    private Integer matchType;

    /**
     * 优先级，数值越小优先级越高（一般长词在前）
     */
    private Integer priority;

    /**
     * 是否生效 1：生效 0：禁用
     */
    private Integer enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 关联的知识库 ID 列表（JSON 数组格式存储）
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String knowledgeBaseIds;

    private String createBy;

    private String updateBy;

    private Date createTime;

    private Date updateTime;
}
