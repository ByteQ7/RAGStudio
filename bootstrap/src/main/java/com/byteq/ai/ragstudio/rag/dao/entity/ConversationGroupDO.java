package com.byteq.ai.ragstudio.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话分组实体类
 * <p>
 * 元宝式对话分组：用户可创建多个分组（类似文件夹），将会话移入分组归类管理，
 * 并可为每个分组设置专属指令（instruction），组内新对话自动注入系统提示。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_conversation_group")
public class ConversationGroupDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    private String name;

    /**
     * 分组专属指令（组内新对话自动注入系统提示）
     */
    private String instruction;

    /**
     * 是否置顶
     */
    private Boolean pinned;

    /**
     * 分组默认知识库 ID JSON 数组（组内对话默认选中，可手动增删）
     */
    private String knowledgeBaseIds;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    @TableLogic
    private Integer deleted;
}
