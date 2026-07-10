package com.byteq.ai.ragstudio.aimodel.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 连通性检查结果返回对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectivityResultVO {
    /** 是否连接成功 */
    private boolean success;
    /** 延迟（毫秒） */
    private Long latencyMs;
    /** 错误信息（失败时） */
    private String error;
}
