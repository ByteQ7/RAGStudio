package com.byteq.ai.ragstudio.alert.dao.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_alert_config")
public class AlertConfig {

    @TableId
    private String id;

    /** 是否启用告警 1:启用 0:禁用 */
    private Integer enabled;

    /** SMTP 服务器地址 */
    private String smtpHost;

    /** SMTP 端口 */
    private Integer smtpPort;

    /** SMTP 用户名 */
    private String smtpUsername;

    /** SMTP 密码 */
    private String smtpPassword;

    /** 发件人邮箱 */
    private String fromAddress;

    /** 收件人邮箱 */
    private String toAddress;

    /** 熔断统计时间窗口（小时），1-24 */
    private Integer timeWindowHours;

    /** 窗口内熔断次数阈值，1-10 */
    private Integer failureThreshold;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
