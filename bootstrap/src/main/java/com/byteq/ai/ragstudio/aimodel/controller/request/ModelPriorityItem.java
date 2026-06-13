package com.byteq.ai.ragstudio.aimodel.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 批量调整模型优先级的单项参数
 */
@Data
public class ModelPriorityItem {

    /** 模型 ID（数据库主键） */
    @NotBlank(message = "模型ID不能为空")
    private String id;

    /** 新优先级 */
    @NotNull(message = "优先级不能为空")
    private Integer priority;
}
